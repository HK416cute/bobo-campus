package com.bobo.ai;

import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.vo.AiVos;
import com.bobo.service.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台运营 Copilot：把看板数字变成自然语言周报，并支持只读问答。
 *
 * <p><b>最关键的设计是"只读"</b>：本服务只把统计事实交给模型，<b>不向模型暴露任何写操作</b>
 * （审核通过、封禁用户、改价、改库存都不给）。原因是模型输出是不可信输入，
 * 一旦让它能触发真实业务动作，"模型幻觉"就会直接变成线上事故。
 * 需要模型参与决策时，正确做法是让模型产出"建议"，由人来点击确认。
 *
 * <p>第二关键是<b>只允许基于给定事实作答</b>：事实来自 {@link StatsService} 的真实统计，
 * 提示词明确要求"不要编造事实里没有的数字，缺数据就说缺数据"。
 * 这样即使模型想发挥，也拿不到编造的口子——因为审核者（管理员）看得到事实快照原文。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCopilotService {

    /** 事实快照里允许透露的键，防止把内部字段顺手塞给模型 */
    private static final List<String> FACT_KEYS = List.of(
            "userTotal", "userToday", "itemTotal", "itemPending", "itemOnSale", "itemSold",
            "orderTotal", "orderToday", "gmv", "reviewTotal", "avgScore", "favoriteTotal",
            "reportPending", "refunding");

    private static final String REPORT_SYSTEM_PROMPT = """
            你是校园二手交易平台的运营分析助手。根据给定的统计数据写一份简明中文运营周报。

            要求：
            - 只使用给定事实里的数字，绝对不要编造或推算事实中没有的数据
            - 结构：① 一句话总览 ② 交易情况（订单量、成交额、趋势） ③ 内容与审核（待审核、举报、退款）
              ④ 值得关注的问题（最多 3 条，每条给出可执行的动作建议）
            - 语言简洁，不要客套话，不要说"综上所述"
            - 如果某项数据缺失，直接写"该项暂无数据"，不要猜测

            【输出格式，必须严格遵守】
            - 直接输出可读的中文正文，用「一、二、三、四」或短横线分节；不要输出 JSON、不要输出代码块围栏、
              不要把事实快照再抄一遍、不要输出任何字段名（如 overview / trading 这类 key 一律不要出现）
            - 上面的"结构"指正文的段落顺序，不是 JSON 字段
            """;

    private static final String ASK_SYSTEM_PROMPT = """
            你是校园二手交易平台的运营数据助手。请只根据给定的统计数据回答管理员的问题。

            要求：
            - 只能使用事实里出现的数字；事实里没有的信息，直接回答"当前统计口径里没有这项数据"
            - 不要编造、不要外推、不要给出与事实冲突的结论
            - 回答控制在 200 字内，需要时用短列表，不要把事实原样全部复述
            - 直接输出中文正文，不要输出 JSON 或代码块
            """;

    private final AiExecutor aiExecutor;
    private final StatsService statsService;

    /** 生成运营周报 */
    public AiVos.AdminReportVO weeklyReport(int days) {
        Map<String, Object> facts = collectFacts(days);
        AiOutcome<String> outcome = aiExecutor.execute(
                AiCallLog.SCENE_ADMIN_REPORT,
                REPORT_SYSTEM_PROMPT,
                "统计数据（JSON）：\n" + facts,
                this::asReadableText,
                () -> fallbackReport(facts));

        AiVos.AdminReportVO vo = new AiVos.AdminReportVO();
        vo.setReport(outcome.value());
        vo.setFacts(facts);
        vo.setSource(outcome.source());
        vo.setDegradeNote(outcome.degradeNote());
        vo.setLogId(outcome.logId());
        vo.setLatencyMs(outcome.latencyMs());
        return vo;
    }

    /** 只读问答 */
    public AiVos.AdminReportVO ask(String question) {
        Map<String, Object> facts = collectFacts(7);
        AiOutcome<String> outcome = aiExecutor.execute(
                AiCallLog.SCENE_ADMIN_ASK,
                ASK_SYSTEM_PROMPT,
                "统计数据（JSON）：\n" + facts + "\n\n管理员的问题：" + question,
                this::asReadableText,
                () -> "AI 未启用或暂不可用，以下是最新的平台统计事实，请自行查阅：\n" + facts);

        AiVos.AdminReportVO vo = new AiVos.AdminReportVO();
        vo.setReport(outcome.value());
        vo.setFacts(facts);
        vo.setSource(outcome.source());
        vo.setDegradeNote(outcome.degradeNote());
        vo.setLogId(outcome.logId());
        vo.setLatencyMs(outcome.latencyMs());
        return vo;
    }

    /**
     * 把模型回复规整成可读文本；空内容返回 null（触发规则降级）。
     *
     * <p><b>为什么要这一步</b>：周报/问答要的是自然语言，但模型看到提示词里有"结构"二字
     * 就可能直接返回 JSON（实测复现过：整段周报是一个 JSON 对象，字段名 overview/trading 全漏了出来）。
     * 只做"非空校验"就会把 JSON 原样丢给管理员看。
     *
     * <p>处理方式：识别出形如 JSON 对象的回复时，去掉外层符号并做可读化换行，
     * 保证管理员至少能看到内容而不是一坨转义字符；真正正确的输出（正文）原样保留。
     */
    private String asReadableText(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 去掉可能出现的 Markdown 围栏
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            if (nl > 0) {
                text = text.substring(nl + 1).trim();
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence).trim();
            }
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            // 模型没听话给了 JSON：把字段名去掉，尽量还原成人能读的段落
            String body = text.substring(1, text.length() - 1);
            String readable = body
                    .replaceAll("\"([A-Za-z_]+)\"\\s*:", "")
                    .replace("\",\"", "\n")
                    .replace("\", \"", "\n")
                    .replace("\"", "")
                    .replace("},", "\n")
                    .replace("]", "\n")
                    .replace("[", "")
                    .replace("}", "").trim();
            log.warn("运营报告模型返回了 JSON 而非自然语言，已做可读化降级处理（建议检查提示词）");
            return readable.isEmpty() ? text : readable;
        }
        return text;
    }

    /**
     * 采集事实快照：全部来自 {@link StatsService} 的真实统计。
     * 只挑白名单字段，避免把内部结构顺手交给外部模型。
     */
    public Map<String, Object> collectFacts(int days) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<String, Object> dashboard = statsService.dashboard();
        for (String key : FACT_KEYS) {
            if (dashboard.containsKey(key)) {
                facts.put(key, dashboard.get(key));
            }
        }
        // 待处理事项（举报/待审核/退款中）
        facts.put("pending", statsService.pending());
        // 近 N 天趋势：只保留日期与单量，避免提示词过长（成本与信息量要平衡）
        List<Map<String, Object>> trend = statsService.trend(days);
        facts.put("trendDays", days);
        facts.put("trend", trend.stream()
                .map(row -> Map.of("day", row.get("day"),
                        "orderCount", row.get("orderCount"),
                        "amount", row.get("amount")))
                .toList());
        // 分类分布：只取前 10，避免大平台把提示词撑爆
        List<Map<String, Object>> categories = statsService.categoryDistribution();
        facts.put("topCategories", categories.stream().limit(10).toList());
        facts.put("categoryTotal", categories.size());
        return facts;
    }

    /**
     * 规则兜底报告：把事实整理成人能读的条目。
     * 没有模型时管理员仍能看到一份完整汇总，而不是一片空白——
     * "AI 不可用"不该等于"功能不可用"。
     */
    private String fallbackReport(Map<String, Object> facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【规则汇总】AI 未启用或暂不可用，以下为平台统计事实：\n");
        sb.append("· 用户：累计 ").append(facts.get("userTotal"))
                .append("，今日新增 ").append(facts.get("userToday")).append('\n');
        sb.append("· 商品：累计 ").append(facts.get("itemTotal"))
                .append("，在售 ").append(facts.get("itemOnSale"))
                .append("，待审核 ").append(facts.get("itemPending"))
                .append("，已售 ").append(facts.get("itemSold")).append('\n');
        sb.append("· 订单：累计 ").append(facts.get("orderTotal"))
                .append("，今日 ").append(facts.get("orderToday"))
                .append("，累计成交额 ").append(facts.get("gmv")).append(" 元\n");
        sb.append("· 评价：").append(facts.get("reviewTotal"))
                .append(" 条，平均分 ").append(facts.get("avgScore"))
                .append("；收藏 ").append(facts.get("favoriteTotal")).append('\n');
        sb.append("· 待处理：").append(facts.get("pending")).append('\n');
        return sb.toString();
    }
}
