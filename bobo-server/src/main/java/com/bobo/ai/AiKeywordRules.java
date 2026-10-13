package com.bobo.ai;

import com.bobo.domain.entity.AiModeration;
import com.bobo.domain.vo.AiVos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地关键词规则：内容审核与发布助手的<b>前置过滤 + 降级兜底</b>。
 *
 * <p>两个作用，缺一不可：
 * <ol>
 *   <li><b>前置过滤省钱</b>：命中明确违禁词直接判定，不调用模型。
 *       这一层能把最脏的流量挡在模型之外，是成本控制的第一道闸门。</li>
 *   <li><b>降级兜底可用</b>：没有 API key 或模型挂掉时，审核与风险标记仍有结论，
 *       而不是"AI 不可用 → 审核直接放行"（后者等于把风险内容全放进站）。</li>
 * </ol>
 *
 * <p>规则写成"词组 + 权重 + 类目"而不是简单的 contains 列表，
 * 是为了让风险分级（低/中/高）和理由可解释——管理员看到的是
 * 「命中「代写论文」→ 学术作弊」而不是一个光秃秃的红点。
 *
 * <p>注意：<b>规则只用于"初筛与兜底"，不用来宣称效果</b>。
 * 模型相比规则多抓到了什么，由 {@code decision_source} 字段区分统计，
 * 这样"模型增益"才是可度量的，而不是感觉。
 *
 * @author bobo
 */
@Slf4j
@Component
public class AiKeywordRules {

    /** 风险类目常量 */
    public static final String CATEGORY_ILLEGAL = "违禁物品";
    public static final String CATEGORY_ACADEMIC = "学术作弊";
    public static final String CATEGORY_FRAUD = "欺诈风险";
    public static final String CATEGORY_CONTACT = "联系方式引流";
    public static final String CATEGORY_ADULT = "不当内容";

    /** 词 → (类目, 风险权重) */
    private record Rule(String category, int weight) {
    }

    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    static {
        // 违禁物品：高风险，命中即建议拦截
        for (String w : List.of("管制刀具", "仿真枪", "气枪", "弹药", "毒品", "冰毒", "大麻",
                "处方药", "违禁药", "假证", "假学生证", "银行卡出租", "身份证出租", "烟酒代购", "电子烟")) {
            RULES.put(w, new Rule(CATEGORY_ILLEGAL, 3));
        }
        // 学术作弊：校园场景高发
        for (String w : List.of("代写论文", "代写作业", "论文代写", "替考", "代考", "包过",
                "考研答案", "四六级答案", "作弊器材", "答案出售")) {
            RULES.put(w, new Rule(CATEGORY_ACADEMIC, 3));
        }
        // 欺诈信号：中风险，提示管理员重点看
        for (String w : List.of("先付款后发货", "私下转账", "微信转账", "要求定金", "保证金",
                "刷单", "返利", "高额回报", "投资理财")) {
            RULES.put(w, new Rule(CATEGORY_FRAUD, 2));
        }
        // 联系方式引流：中低风险，校园平台通常要求站内沟通
        for (String w : List.of("加微信", "加QQ", "微信号", "扫码加", "私聊我", "电话联系")) {
            RULES.put(w, new Rule(CATEGORY_CONTACT, 1));
        }
        // 不当内容
        for (String w : List.of("色情", "裸照", "约炮")) {
            RULES.put(w, new Rule(CATEGORY_ADULT, 3));
        }
    }

    /**
     * 按关键词规则判定风险。
     *
     * @param content 待判定内容，null 视为低风险
     * @return 判定结果；{@code matched=false} 表示规则未命中（不代表安全，只代表规则没话说）
     */
    public RuleResult evaluate(String content) {
        if (content == null || content.isBlank()) {
            return new RuleResult(false, AiModeration.RISK_LOW, List.of(), null);
        }
        String normalized = content.toLowerCase();
        List<String> categories = new ArrayList<>();
        List<String> hits = new ArrayList<>();
        int maxWeight = 0;
        for (Map.Entry<String, Rule> entry : RULES.entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase())) {
                hits.add(entry.getKey());
                if (!categories.contains(entry.getValue().category())) {
                    categories.add(entry.getValue().category());
                }
                maxWeight = Math.max(maxWeight, entry.getValue().weight());
            }
        }
        if (hits.isEmpty()) {
            return new RuleResult(false, AiModeration.RISK_LOW, List.of(), null);
        }
        int level = maxWeight >= 3 ? AiModeration.RISK_HIGH
                : (maxWeight == 2 ? AiModeration.RISK_MEDIUM : AiModeration.RISK_LOW);
        String reason = "命中本地关键词规则：" + String.join("、", hits);
        return new RuleResult(true, level, categories, reason);
    }

    /**
     * 规则命中结果。
     *
     * @param matched    是否命中
     * @param riskLevel  0 低 / 1 中 / 2 高
     * @param categories 命中类目
     * @param reason     可展示给管理员的理由
     */
    public record RuleResult(boolean matched, int riskLevel, List<String> categories, String reason) {

        /** 转成视图对象（降级路径用，source 固定为 rule） */
        public AiVos.ModerationVO toVo(String targetType, Long targetId, String degradeNote) {
            AiVos.ModerationVO vo = new AiVos.ModerationVO();
            vo.setTargetType(targetType);
            vo.setTargetId(targetId);
            vo.setRiskLevel(riskLevel);
            vo.setRiskCategories(categories);
            vo.setReason(reason);
            vo.setAutoReject(riskLevel >= AiModeration.RISK_HIGH);
            vo.setSource(AiExecutor.SOURCE_RULE);
            vo.setDegradeNote(degradeNote);
            return vo;
        }
    }

    /**
     * 给发布助手用的轻量风险提示：只做违禁/作弊两类（欺诈与引流在商品描述里太常见，
     * 直接标红会淹没真正的问题）。返回 null 表示无风险。
     */
    public List<String> riskFlagsFor(String content) {
        RuleResult result = evaluate(content);
        if (!result.matched()) {
            return List.of();
        }
        List<String> flags = new ArrayList<>();
        for (String category : result.categories()) {
            if (CATEGORY_ILLEGAL.equals(category) || CATEGORY_ACADEMIC.equals(category)
                    || CATEGORY_ADULT.equals(category)) {
                flags.add(category);
            }
        }
        return flags;
    }
}
