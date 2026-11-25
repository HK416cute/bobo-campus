package com.bobo.ai.match;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.ai.AiOutcome;
import com.bobo.ai.AiTextRouter;
import com.bobo.ai.SafeJsonParser;
import com.bobo.common.exception.BizException;
import com.bobo.config.props.AiProperties;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.MatchMessage;
import com.bobo.domain.entity.MatchRequest;
import com.bobo.domain.entity.Notice;
import com.bobo.domain.entity.User;
import com.bobo.domain.vo.AiUpgradeVos;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.MatchMessageMapper;
import com.bobo.mapper.MatchRequestMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import com.bobo.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块一：多智能体自动撮合。
 *
 * <p>编排是<b>服务端确定性状态机</b>，不是让两个模型自由聊天：
 * <pre>
 *   买家 Agent 分析商品/行情 → 首轮出价
 *        ↓
 *   卖家 Agent 评估（标价、同类成交、轮次） → 接受 / 还价 / 拒绝
 *        ↓
 *   买家 Agent 评估（预算、还价） → 接受 / 再还价 / 拒绝
 *        ↓  最多 3 轮，或 30 秒预算用完
 *   达成 → 待双方确认；未达成 → 结束并建议私信
 * </pre>
 *
 * <p><b>为什么不让模型自己决定何时停止</b>：多轮 Agent 最典型的线上问题是
 * 「两个模型互相客气，永远谈不完」。轮次、超时、价格边界全部由服务端控制，
 * 模型只负责「这一轮说什么价、怎么措辞」。
 *
 * <p><b>人工确认原则</b>：Agent 达成只是一条建议，必须买卖双方各自点确认
 * 才进入「已达成」，之后买家可按撮合价下单；系统不会自动生成订单。
 *
 * <p><b>降级</b>：没有 Key、模型超时、输出不可解析时，由 {@link MatchRuleEngine}
 * 接管对应轮次，用户仍能拿到一个可确认的规则协商结果（source=rule）。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    /** 待确认有效期：超过后自动过期，避免长期挂着的“僵尸建议” */
    private static final int EXPIRE_HOURS = 24;

    private static final String BUYER_SYSTEM = """
            你是 bobo 校园二手交易平台的买家 Agent，代表买家与卖家 Agent 协商价格。
            你不能替买家付款、不能承诺下单；最终是否成交由买家在页面上点确认。

            谈判目标：在买家预算内争取更低价格，语气礼貌、简洁，不纠缠。
            输出 JSON：
            {"action":"offer|counter|accept|reject","price":数字或null,"message":"对卖家说的一句话","reason":"给买家看的理由"}

            规则：
            1. 首次发言 action 必须是 offer（出价）；
            2. 收到卖家还价后，可用 counter（再还价）、accept（接受）或 reject（拒绝）；
            3. offer/counter 的 price 必须大于 0，且不得超过卖家当前要价；
            4. accept 时 price 可省略，服务端会按卖家当前要价处理；
            5. 预算上限是硬约束，超出太多时应 reject，而不是硬撑。
            """;

    private static final String SELLER_SYSTEM = """
            你是 bobo 校园二手交易平台的卖家 Agent，代表卖家利益与买家 Agent 协商价格。
            你不能替卖家同意下单；最终是否成交由卖家在页面上点确认。

            谈判目标：在保证成交可能性的前提下，尽量接近标价，语气礼貌、简洁。
            输出 JSON：
            {"action":"accept|counter|reject","price":数字或null,"message":"对买家说的一句话","reason":"给卖家看的理由"}

            规则：
            1. 只处理价格，不承诺发货时间、赠品或平台外交易；
            2. accept 表示接受买家当前出价，price 可省略；
            3. counter 的 price 必须高于买家出价、且不高于商品标价；
            4. 买家出价明显低于商品价值时，可以 reject；
            5. 轮次越往后，可接受的成交价可以适度靠近买家，以促成交易。
            """;

    private final MatchRequestMapper matchMapper;
    private final MatchMessageMapper messageMapper;
    private final ItemMapper itemMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;
    private final AiTextRouter textRouter;
    private final SafeJsonParser jsonParser;
    private final MatchRuleEngine ruleEngine;
    private final MatchContextService contextService;
    private final AiProperties aiProperties;

    // ================================================================== 发起撮合

    /**
     * 买家发起撮合：创建撮合单并同步跑完协商（最多 3 轮 / 30 秒）。
     *
     * <p>同步而不是异步，是为了让买家点完就能看到协商过程；
     * 代价是请求耗时较长，因此模型调用全部有超时，且总预算到了就停止。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：协商里包含多次外部 HTTP 调用，
     * 如果把整段包进事务，会长时间占用数据库连接（最坏 30 秒），高并发时直接拖垮连接池。
     * 撮合单先落库、协商完再更新；中途异常留下的「协商中」记录会被过期逻辑清理。
     */
    public AiUpgradeVos.MatchDetailVO create(Dtos.MatchCreateDTO dto) {
        Long buyerId = AuthContext.userId();
        Item item = itemMapper.selectById(dto.itemId());
        if (item == null || item.getStatus() == null) {
            throw BizException.notFound("商品不存在或已被删除");
        }
        if (item.getStatus() != Item.STATUS_ON_SALE) {
            throw new BizException("商品已下架或已售出，无法发起撮合");
        }
        if (buyerId.equals(item.getSellerId())) {
            throw new BizException("不能对自己的商品发起撮合");
        }
        if (item.getPrice() == null || item.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("商品价格异常，无法发起撮合");
        }

        BigDecimal budgetMin = dto.budgetMin();
        BigDecimal budgetMax = dto.budgetMax();
        if (budgetMin != null && budgetMax != null && budgetMin.compareTo(budgetMax) > 0) {
            BigDecimal tmp = budgetMin;
            budgetMin = budgetMax;
            budgetMax = tmp;
        }

        Long active = matchMapper.selectCount(Wrappers.<MatchRequest>lambdaQuery()
                .eq(MatchRequest::getBuyerId, buyerId)
                .eq(MatchRequest::getItemId, item.getItemId())
                .in(MatchRequest::getStatus,
                        MatchRequest.STATUS_NEGOTIATING, MatchRequest.STATUS_AWAITING_CONFIRM));
        if (active != null && active > 0) {
            throw new BizException("你已对这件商品发起过撮合，请到「我的撮合」查看进度");
        }

        MatchRequest match = new MatchRequest();
        match.setBuyerId(buyerId);
        match.setSellerId(item.getSellerId());
        match.setItemId(item.getItemId());
        match.setStatus(MatchRequest.STATUS_NEGOTIATING);
        match.setBudgetMin(budgetMin);
        match.setBudgetMax(budgetMax);
        match.setDemandText(clip(dto.demandText(), 500));
        match.setPreferredTime(clip(dto.preferredTime(), 64));
        match.setPreferredPlace(clip(dto.preferredPlace(), 128));
        match.setTradeType(dto.tradeType());
        match.setRounds(0);
        match.setBuyerConfirmed(0);
        match.setSellerConfirmed(0);
        match.setSource(MatchRequest.SOURCE_RULE);
        match.setExpireTime(LocalDateTime.now().plusHours(EXPIRE_HOURS));
        match.setCreateTime(LocalDateTime.now());
        match.setUpdateTime(LocalDateTime.now());
        try {
            matchMapper.insert(match);
        } catch (Exception e) {
            log.warn("创建撮合单失败（是否已执行 mysql/init/06-ai-upgrade.sql？）：{}", e.getMessage());
            throw new BizException("智能撮合表未初始化，请管理员先执行 mysql/init/06-ai-upgrade.sql");
        }

        saveMessage(match.getMatchId(), 1, MatchMessage.SENDER_SYSTEM, MatchMessage.ACTION_NOTE,
                null, "买家发起智能撮合：预算 "
                        + budgetText(budgetMin, budgetMax) + "，诉求：" + match.getDemandText()
                        + "。两个 Agent 开始协商。", "rule", null);

        MatchContextService.Facts facts = contextService.collect(item, budgetMin, budgetMax,
                match.getDemandText(), match.getPreferredTime(), match.getPreferredPlace());
        NegotiationResult result = negotiate(match, item, facts);

        match.setStatus(result.agreed() ? MatchRequest.STATUS_AWAITING_CONFIRM
                : MatchRequest.STATUS_FAILED);
        match.setAgreedPrice(result.agreedPrice());
        match.setRounds(result.rounds());
        match.setSummary(clip(result.summary(), 500));
        match.setSource(result.source());
        match.setLogId(result.lastLogId());
        match.setExpireTime(result.agreed()
                ? LocalDateTime.now().plusHours(EXPIRE_HOURS) : LocalDateTime.now());
        match.setUpdateTime(LocalDateTime.now());
        matchMapper.updateById(match);

        saveMessage(match.getMatchId(), result.rounds() + 1, MatchMessage.SENDER_SYSTEM,
                result.agreed() ? MatchMessage.ACTION_ACCEPT : MatchMessage.ACTION_NOTE,
                result.agreedPrice(), result.summary(), "rule", null);

        notificationService.notify("收到智能撮合请求",
                "买家对「" + item.getTitle() + "」发起撮合，Agent 协商结果："
                        + (result.agreed() ? "建议成交价 ¥" + result.agreedPrice()
                        : "未达成一致") + "。请到「我的撮合」查看并确认。",
                Notice.TYPE_SYSTEM, match.getMatchId(), item.getSellerId());

        return detail(match.getMatchId());
    }

    // ================================================================== 协商编排

    private NegotiationResult negotiate(MatchRequest match, Item item, MatchContextService.Facts facts) {
        long start = System.currentTimeMillis();
        long budgetMs = Math.max(5_000, aiProperties.getMatchTimeoutMs());
        int maxRounds = Math.max(1, aiProperties.getMatchMaxRounds());
        int round = 1;
        int rounds = 0;
        boolean modelUsed = false;
        boolean ruleUsed = false;
        boolean timedOut = false;
        Long lastLogId = null;
        BigDecimal agreed = null;
        String endReason = "exhausted";

        List<String> history = new ArrayList<>();
        MatchRuleEngine.Move openingFallback = ruleEngine.buyerOpen(
                item.getPrice(), facts.budgetMin(), facts.budgetMax());
        AgentMove opening = askBuyer(match, item, facts, history, null, openingFallback, true);
        modelUsed |= opening.modelUsed();
        ruleUsed |= !opening.modelUsed();
        lastLogId = opening.logId() != null ? opening.logId() : lastLogId;
        append(match.getMatchId(), round, opening, history);
        BigDecimal currentOffer = opening.move().price();

        for (; round <= maxRounds; round++) {
            if (System.currentTimeMillis() - start > budgetMs) {
                timedOut = true;
                endReason = "timeout";
                break;
            }

            MatchRuleEngine.Move sellerFallback = ruleEngine.sellerReply(
                    item.getPrice(), currentOffer, round);
            AgentMove seller = askSeller(match, item, facts, history, currentOffer, sellerFallback);
            modelUsed |= seller.modelUsed();
            ruleUsed |= !seller.modelUsed();
            lastLogId = seller.logId() != null ? seller.logId() : lastLogId;
            append(match.getMatchId(), round, seller, history);
            rounds = round;
            if (seller.move().action() == MatchRuleEngine.Action.ACCEPT) {
                agreed = seller.move().price();
                endReason = "seller_accept";
                break;
            }
            if (seller.move().action() == MatchRuleEngine.Action.REJECT) {
                endReason = "seller_reject";
                break;
            }

            if (System.currentTimeMillis() - start > budgetMs) {
                timedOut = true;
                endReason = "timeout";
                break;
            }

            BigDecimal sellerPrice = seller.move().price();
            MatchRuleEngine.Move buyerFallback = ruleEngine.buyerReply(
                    item.getPrice(), sellerPrice, facts.budgetMin(), facts.budgetMax());
            AgentMove buyer = askBuyer(match, item, facts, history, sellerPrice, buyerFallback, false);
            modelUsed |= buyer.modelUsed();
            ruleUsed |= !buyer.modelUsed();
            lastLogId = buyer.logId() != null ? buyer.logId() : lastLogId;
            append(match.getMatchId(), round, buyer, history);
            if (buyer.move().action() == MatchRuleEngine.Action.ACCEPT) {
                agreed = buyer.move().price();
                endReason = "buyer_accept";
                break;
            }
            if (buyer.move().action() == MatchRuleEngine.Action.REJECT) {
                endReason = "buyer_reject";
                break;
            }
            currentOffer = buyer.move().price();
        }

        String source = modelUsed && ruleUsed ? MatchRequest.SOURCE_MIXED
                : (modelUsed ? MatchRequest.SOURCE_MODEL : MatchRequest.SOURCE_RULE);
        String summary = summary(agreed, rounds, timedOut, endReason, item.getPrice());
        return new NegotiationResult(agreed, rounds, source, summary, lastLogId, timedOut);
    }

    private AgentMove askBuyer(MatchRequest match, Item item, MatchContextService.Facts facts,
                               List<String> history, BigDecimal sellerPrice,
                               MatchRuleEngine.Move fallback, boolean opening) {
        String prompt = "【商品事实】\n" + contextService.marketContext(facts)
                + "\n【买家诉求】\n" + contextService.demandContext(facts)
                + "\n【协商记录】\n" + (history.isEmpty() ? "（尚无）\n" : String.join("\n", history) + "\n")
                + (opening
                ? "\n请给出你的首轮出价。"
                : "\n卖家当前要价：¥" + money(sellerPrice) + "。请决定：接受、再还价还是拒绝。");

        AiOutcome<MatchRuleEngine.Move> outcome = textRouter.execute(
                AiCallLog.SCENE_MATCH_BUYER,
                BUYER_SYSTEM,
                prompt,
                raw -> parseBuyer(raw, item, facts, sellerPrice),
                () -> fallback);
        return new AgentMove(outcome.value(), outcome.degraded() ? "rule" : "model",
                outcome.logId(), !outcome.degraded());
    }

    private AgentMove askSeller(MatchRequest match, Item item, MatchContextService.Facts facts,
                                List<String> history, BigDecimal buyerOffer,
                                MatchRuleEngine.Move fallback) {
        String prompt = "【商品事实】\n" + contextService.marketContext(facts)
                + "\n【买家诉求】\n" + contextService.demandContext(facts)
                + "\n【协商记录】\n" + (history.isEmpty() ? "（尚无）\n" : String.join("\n", history) + "\n")
                + "\n买家当前出价：¥" + money(buyerOffer) + "。请决定：接受、还价还是拒绝。";

        AiOutcome<MatchRuleEngine.Move> outcome = textRouter.execute(
                AiCallLog.SCENE_MATCH_SELLER,
                SELLER_SYSTEM,
                prompt,
                raw -> parseSeller(raw, item, buyerOffer),
                () -> fallback);
        return new AgentMove(outcome.value(), outcome.degraded() ? "rule" : "model",
                outcome.logId(), !outcome.degraded());
    }

    // ================================================================== 模型输出校验

    private MatchRuleEngine.Move parseBuyer(String raw, Item item, MatchContextService.Facts facts,
                                            BigDecimal sellerPrice) {
        return jsonParser.parse(raw, node -> {
            MatchRuleEngine.Action action = MatchRuleEngine.Action.of(text(node, "action"));
            if (action == null) {
                return null;
            }
            String message = text(node, "message");
            BigDecimal price = decimal(node, "price");
            BigDecimal itemPrice = item.getPrice();

            if (action == MatchRuleEngine.Action.ACCEPT) {
                if (sellerPrice == null) {
                    return null;
                }
                if (facts.budgetMax() != null
                        && sellerPrice.compareTo(facts.budgetMax().multiply(new BigDecimal("1.05"))) > 0) {
                    // 超出预算 5% 以上：模型无权“替买家同意”，交给规则还价/拒绝
                    return null;
                }
                return new MatchRuleEngine.Move(MatchRuleEngine.Sender.BUYER, action,
                        ruleEngine.money(sellerPrice), safeMessage(message, "这个价格可以接受。"));
            }
            if (action == MatchRuleEngine.Action.REJECT) {
                return new MatchRuleEngine.Move(MatchRuleEngine.Sender.BUYER, action, null,
                        safeMessage(message, "这个价格超出预算了，暂时不考虑。"));
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            // 出价不能高于卖家当前要价；高于则等价于接受
            if (sellerPrice != null) {
                if (price.compareTo(sellerPrice) >= 0) {
                    return new MatchRuleEngine.Move(MatchRuleEngine.Sender.BUYER,
                            MatchRuleEngine.Action.ACCEPT, ruleEngine.money(sellerPrice),
                            safeMessage(message, "就按这个价格吧。"));
                }
            } else {
                price = price.min(itemPrice);
            }
            if (facts.budgetMax() != null) {
                price = price.min(facts.budgetMax().multiply(new BigDecimal("1.05")));
            }
            price = ruleEngine.money(price.max(BigDecimal.ONE));
            return new MatchRuleEngine.Move(MatchRuleEngine.Sender.BUYER, action, price,
                    safeMessage(message, "我出价 ¥" + price.toPlainString() + "。"));
        });
    }

    private MatchRuleEngine.Move parseSeller(String raw, Item item, BigDecimal buyerOffer) {
        return jsonParser.parse(raw, node -> {
            if (buyerOffer == null) {
                return null;
            }
            MatchRuleEngine.Action action = MatchRuleEngine.Action.of(text(node, "action"));
            if (action == null || action == MatchRuleEngine.Action.OFFER) {
                return null;
            }
            String message = text(node, "message");
            BigDecimal price = decimal(node, "price");
            BigDecimal itemPrice = item.getPrice();

            if (action == MatchRuleEngine.Action.ACCEPT) {
                return new MatchRuleEngine.Move(MatchRuleEngine.Sender.SELLER, action,
                        ruleEngine.money(buyerOffer), safeMessage(message, "这个价格可以，成交。"));
            }
            if (action == MatchRuleEngine.Action.REJECT) {
                return new MatchRuleEngine.Move(MatchRuleEngine.Sender.SELLER, action, null,
                        safeMessage(message, "这个价格暂时不能接受。"));
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            // 还价必须高于买家出价；低于出价的“还价”等价于接受
            if (price.compareTo(buyerOffer) <= 0) {
                return new MatchRuleEngine.Move(MatchRuleEngine.Sender.SELLER,
                        MatchRuleEngine.Action.ACCEPT, ruleEngine.money(buyerOffer),
                        safeMessage(message, "就按你说的价格成交。"));
            }
            price = price.min(itemPrice.multiply(new BigDecimal("0.99")));
            price = ruleEngine.money(price.max(buyerOffer.add(new BigDecimal("0.01"))));
            return new MatchRuleEngine.Move(MatchRuleEngine.Sender.SELLER, action, price,
                    safeMessage(message, "最低 ¥" + price.toPlainString() + "。"));
        });
    }

    // ================================================================== 确认 / 取消 / 下单

    /** 买卖双方各自确认；两边都接受才算达成，任一方拒绝则失败 */
    @Transactional(rollbackFor = Exception.class)
    public AiUpgradeVos.MatchDetailVO confirm(Long matchId, Dtos.MatchConfirmDTO dto) {
        MatchRequest match = requireMatch(matchId);
        expireIfNeeded(match);
        Long userId = AuthContext.userId();
        boolean buyer = userId.equals(match.getBuyerId());
        boolean seller = userId.equals(match.getSellerId());
        if (!buyer && !seller) {
            throw BizException.forbidden("只有买卖双方可以确认撮合");
        }
        if (match.getStatus() == MatchRequest.STATUS_AGREED) {
            return detail(matchId);
        }
        if (match.getStatus() != MatchRequest.STATUS_AWAITING_CONFIRM) {
            throw new BizException("当前撮合状态不可确认（可能已过期或未达成）");
        }

        boolean accepted = Boolean.TRUE.equals(dto.accepted());
        String note = clip(dto.note(), 255);
        if (buyer) {
            match.setBuyerConfirmed(accepted ? 1 : 0);
            match.setBuyerNote(note);
        } else {
            match.setSellerConfirmed(accepted ? 1 : 0);
            match.setSellerNote(note);
        }

        if (!accepted) {
            match.setStatus(MatchRequest.STATUS_FAILED);
            match.setSummary((buyer ? "买家" : "卖家") + "拒绝了撮合建议"
                    + (StringUtils.hasText(note) ? "：" + note : "") + "。");
            match.setUpdateTime(LocalDateTime.now());
            matchMapper.updateById(match);
            saveMessage(matchId, 0, MatchMessage.SENDER_SYSTEM, MatchMessage.ACTION_REJECT, null,
                    match.getSummary(), "rule", null);
            notificationService.notify("撮合建议未通过",
                    "「" + itemTitle(match.getItemId()) + "」的撮合建议被"
                            + (buyer ? "买家" : "卖家") + "拒绝，可继续浏览或私信沟通。",
                    Notice.TYPE_SYSTEM, matchId, buyer ? match.getSellerId() : match.getBuyerId());
            return detail(matchId);
        }

        boolean both = match.getBuyerConfirmed() == 1 && match.getSellerConfirmed() == 1;
        if (both) {
            match.setStatus(MatchRequest.STATUS_AGREED);
            match.setSummary("双方已确认撮合建议，成交价 ¥" + match.getAgreedPrice()
                    + "。买家可在商品详情页按撮合价下单。");
            match.setExpireTime(null);
        } else {
            match.setSummary("等待另一方确认。当前建议成交价 ¥" + match.getAgreedPrice() + "。");
        }
        match.setUpdateTime(LocalDateTime.now());
        matchMapper.updateById(match);
        saveMessage(matchId, 0, MatchMessage.SENDER_SYSTEM,
                both ? MatchMessage.ACTION_ACCEPT : MatchMessage.ACTION_NOTE, match.getAgreedPrice(),
                match.getSummary(), "rule", null);
        notificationService.notify(both ? "撮合已达成" : "对方已确认撮合",
                both ? "「" + itemTitle(match.getItemId()) + "」撮合达成，成交价 ¥"
                        + match.getAgreedPrice() + "，买家可按此价下单。"
                        : "对方已确认撮合建议，请尽快到「我的撮合」确认。",
                Notice.TYPE_SYSTEM, matchId,
                buyer ? match.getSellerId() : match.getBuyerId());
        return detail(matchId);
    }

    /** 参与者取消协商 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long matchId) {
        MatchRequest match = requireMatch(matchId);
        Long userId = AuthContext.userId();
        if (!userId.equals(match.getBuyerId()) && !userId.equals(match.getSellerId())) {
            throw BizException.forbidden("只有买卖双方可以取消撮合");
        }
        if (match.getStatus() != MatchRequest.STATUS_NEGOTIATING
                && match.getStatus() != MatchRequest.STATUS_AWAITING_CONFIRM) {
            throw new BizException("当前撮合状态不可取消");
        }
        match.setStatus(MatchRequest.STATUS_CANCELLED);
        match.setSummary("撮合已取消。");
        match.setUpdateTime(LocalDateTime.now());
        matchMapper.updateById(match);
        notificationService.notify("撮合已取消",
                "「" + itemTitle(match.getItemId()) + "」的撮合已被"
                        + (userId.equals(match.getBuyerId()) ? "买家" : "卖家") + "取消。",
                Notice.TYPE_SYSTEM, matchId,
                userId.equals(match.getBuyerId()) ? match.getSellerId() : match.getBuyerId());
    }

    /**
     * 订单服务调用：校验撮合已达成、以撮合价下单，并原子把撮合单推进到「已下单」。
     *
     * <p>返回价格由服务端从撮合单读取，<b>绝不接受前端传入的价格</b>，
     * 否则买家可以把 1000 元的商品改成 1 元。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal consumeForOrder(Long matchId, Long itemId) {
        Long buyerId = AuthContext.userId();
        MatchRequest match = requireMatch(matchId);
        if (!buyerId.equals(match.getBuyerId())) {
            throw BizException.forbidden("只有买家可以使用该撮合价下单");
        }
        if (!itemId.equals(match.getItemId())) {
            throw new BizException("撮合单与商品不匹配");
        }
        if (match.getStatus() != MatchRequest.STATUS_AGREED
                || match.getAgreedPrice() == null
                || match.getAgreedPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("撮合尚未双方确认，不能按撮合价下单");
        }
        int rows = matchMapper.update(null, Wrappers.<MatchRequest>lambdaUpdate()
                .eq(MatchRequest::getMatchId, matchId)
                .eq(MatchRequest::getStatus, MatchRequest.STATUS_AGREED)
                .set(MatchRequest::getStatus, MatchRequest.STATUS_ORDERED)
                .set(MatchRequest::getUpdateTime, LocalDateTime.now()));
        if (rows == 0) {
            throw new BizException("撮合单状态已变化，请刷新后重试");
        }
        return match.getAgreedPrice();
    }

    // ================================================================== 查询

    public AiUpgradeVos.MatchDetailVO detail(Long matchId) {
        MatchRequest match = requireMatch(matchId);
        expireIfNeeded(match);
        Long userId = AuthContext.userId();
        boolean admin = AuthContext.isAdmin();
        boolean buyer = userId != null && userId.equals(match.getBuyerId());
        boolean seller = userId != null && userId.equals(match.getSellerId());
        if (!admin && !buyer && !seller) {
            throw BizException.forbidden("无权查看该撮合");
        }

        AiUpgradeVos.MatchDetailVO vo = new AiUpgradeVos.MatchDetailVO();
        fill(vo, match, itemMap(match.getItemId()), userMap(Set.of(match.getBuyerId(), match.getSellerId())),
                lastMessages(Set.of(matchId)).get(matchId));
        vo.setMyRole(admin ? "admin" : (buyer ? "buyer" : "seller"));
        vo.setBudgetMin(match.getBudgetMin());
        vo.setBudgetMax(match.getBudgetMax());
        vo.setDemandText(match.getDemandText());
        vo.setPreferredTime(match.getPreferredTime());
        vo.setPreferredPlace(match.getPreferredPlace());
        vo.setTradeType(match.getTradeType());
        vo.setBuyerNote(match.getBuyerNote());
        vo.setSellerNote(match.getSellerNote());
        vo.setMessages(messages(matchId));
        return vo;
    }

    /** 我的撮合列表：role=buyer / seller */
    public List<AiUpgradeVos.MatchVO> list(String role) {
        Long userId = AuthContext.userId();
        String normalized = "seller".equalsIgnoreCase(role) ? "seller" : "buyer";
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MatchRequest> query =
                Wrappers.<MatchRequest>lambdaQuery()
                        .orderByDesc(MatchRequest::getCreateTime)
                        .last("LIMIT 50");
        if ("seller".equals(normalized)) {
            query.eq(MatchRequest::getSellerId, userId);
        } else {
            query.eq(MatchRequest::getBuyerId, userId);
        }
        List<MatchRequest> matches = matchMapper.selectList(query);
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        for (MatchRequest match : matches) {
            expireIfNeeded(match);
        }

        Map<Long, Item> items = itemMap(matches.stream().map(MatchRequest::getItemId)
                .collect(java.util.stream.Collectors.toSet()));
        Set<Long> userIds = new LinkedHashSet<>();
        for (MatchRequest match : matches) {
            userIds.add(match.getBuyerId());
            userIds.add(match.getSellerId());
        }
        Map<Long, User> users = userMap(userIds);
        Map<Long, String> lastMessages = lastMessages(matches.stream()
                .map(MatchRequest::getMatchId).collect(java.util.stream.Collectors.toSet()));

        List<AiUpgradeVos.MatchVO> result = new ArrayList<>();
        for (MatchRequest match : matches) {
            AiUpgradeVos.MatchVO vo = new AiUpgradeVos.MatchVO();
            fill(vo, match, items, users,
                    lastMessages.getOrDefault(match.getMatchId(), null));
            vo.setMyRole(normalized);
            result.add(vo);
        }
        return result;
    }

    // ================================================================== 组装

    private void fill(AiUpgradeVos.MatchVO vo, MatchRequest match, Map<Long, Item> items,
                      Map<Long, User> users, String lastMessage) {
        Item item = items.get(match.getItemId());
        User buyer = users.get(match.getBuyerId());
        User seller = users.get(match.getSellerId());
        vo.setMatchId(match.getMatchId());
        vo.setItemId(match.getItemId());
        vo.setItemTitle(item == null ? "商品已删除" : item.getTitle());
        vo.setItemCover(item == null ? null : item.getCover());
        vo.setItemPrice(item == null ? null : item.getPrice());
        vo.setBuyerId(match.getBuyerId());
        vo.setSellerId(match.getSellerId());
        vo.setBuyerNickname(buyer == null ? "买家" : buyer.getNickname());
        vo.setSellerNickname(seller == null ? "卖家" : seller.getNickname());
        vo.setStatus(match.getStatus());
        vo.setStatusText(statusText(match.getStatus()));
        vo.setAgreedPrice(match.getAgreedPrice());
        vo.setRounds(match.getRounds());
        vo.setSummary(match.getSummary());
        vo.setBuyerConfirmed(match.getBuyerConfirmed() != null && match.getBuyerConfirmed() == 1);
        vo.setSellerConfirmed(match.getSellerConfirmed() != null && match.getSellerConfirmed() == 1);
        vo.setLastMessage(lastMessage);
        vo.setSource(match.getSource());
        vo.setExpireTime(match.getExpireTime());
        vo.setCreateTime(match.getCreateTime());

        Long userId = AuthContext.userIdOrNull();
        boolean buyerRole = userId != null && userId.equals(match.getBuyerId());
        vo.setAwaitingMyConfirm(match.getStatus() == MatchRequest.STATUS_AWAITING_CONFIRM
                && ((buyerRole && !vo.isBuyerConfirmed()) || (!buyerRole && !vo.isSellerConfirmed())));
        vo.setCanPlaceOrder(buyerRole && match.getStatus() == MatchRequest.STATUS_AGREED);
    }

    private List<AiUpgradeVos.MatchMessageVO> messages(Long matchId) {
        List<MatchMessage> messages = messageMapper.selectList(Wrappers.<MatchMessage>lambdaQuery()
                .eq(MatchMessage::getMatchId, matchId)
                .orderByAsc(MatchMessage::getMessageId));
        List<AiUpgradeVos.MatchMessageVO> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (MatchMessage message : messages) {
            AiUpgradeVos.MatchMessageVO vo = new AiUpgradeVos.MatchMessageVO();
            vo.setMessageId(message.getMessageId());
            vo.setRoundNo(message.getRoundNo());
            vo.setSender(message.getSender());
            vo.setSenderText(senderText(message.getSender()));
            vo.setAction(message.getAction());
            vo.setActionText(actionText(message.getAction()));
            vo.setPrice(message.getPrice());
            vo.setContent(message.getContent());
            vo.setSource(message.getSource());
            vo.setCreateTime(message.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    private Map<Long, Item> itemMap(Long itemId) {
        Map<Long, Item> map = new HashMap<>();
        if (itemId == null) {
            return map;
        }
        Item item = itemMapper.selectById(itemId);
        if (item != null) {
            map.put(itemId, item);
        }
        return map;
    }

    private Map<Long, Item> itemMap(Set<Long> itemIds) {
        Map<Long, Item> map = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return map;
        }
        List<Item> items = itemMapper.selectBatchIds(itemIds);
        if (items != null) {
            for (Item item : items) {
                map.put(item.getItemId(), item);
            }
        }
        return map;
    }

    private Map<Long, User> userMap(Set<Long> userIds) {
        Map<Long, User> map = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return map;
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        if (users != null) {
            for (User user : users) {
                map.put(user.getUserId(), user);
            }
        }
        return map;
    }

    private Map<Long, String> lastMessages(Set<Long> matchIds) {
        Map<Long, String> map = new HashMap<>();
        if (matchIds == null || matchIds.isEmpty()) {
            return map;
        }
        List<MatchMessage> messages = messageMapper.selectList(Wrappers.<MatchMessage>lambdaQuery()
                .in(MatchMessage::getMatchId, matchIds)
                .orderByAsc(MatchMessage::getMessageId));
        if (messages != null) {
            for (MatchMessage message : messages) {
                map.put(message.getMatchId(), message.getContent());
            }
        }
        return map;
    }

    // ================================================================== 工具

    private MatchRequest requireMatch(Long matchId) {
        MatchRequest match = matchMapper.selectById(matchId);
        if (match == null) {
            throw BizException.notFound("撮合单不存在");
        }
        return match;
    }

    /** 过期只做懒判断：读列表/详情时发现过期就落库，不额外起定时任务 */
    private void expireIfNeeded(MatchRequest match) {
        if (match.getStatus() == null || match.getExpireTime() == null) {
            return;
        }
        if ((match.getStatus() == MatchRequest.STATUS_NEGOTIATING
                || match.getStatus() == MatchRequest.STATUS_AWAITING_CONFIRM)
                && match.getExpireTime().isBefore(LocalDateTime.now())) {
            MatchRequest update = new MatchRequest();
            update.setMatchId(match.getMatchId());
            update.setStatus(MatchRequest.STATUS_EXPIRED);
            update.setSummary("撮合已过期，请重新发起。");
            matchMapper.updateById(update);
            match.setStatus(MatchRequest.STATUS_EXPIRED);
            match.setSummary(update.getSummary());
        }
    }

    private void append(Long matchId, int round, AgentMove agent, List<String> history) {
        MatchRuleEngine.Move move = agent.move();
        saveMessage(matchId, round, move.sender().value(), move.action().value(),
                move.price(), move.content(), agent.source(), agent.logId());
        history.add("第" + round + "轮 " + (move.sender() == MatchRuleEngine.Sender.BUYER ? "买家" : "卖家")
                + " Agent：" + actionText(move.action().value())
                + (move.price() == null ? "" : " ¥" + move.price().toPlainString())
                + "。" + move.content());
    }

    private void saveMessage(Long matchId, int round, String sender, String action,
                             BigDecimal price, String content, String source, Long logId) {
        MatchMessage message = new MatchMessage();
        message.setMatchId(matchId);
        message.setRoundNo(round);
        message.setSender(sender);
        message.setAction(action);
        message.setPrice(price);
        message.setContent(clip(content, 1000));
        message.setSource(source);
        message.setLogId(logId);
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);
    }

    private String summary(BigDecimal agreed, int rounds, boolean timedOut, String reason,
                           BigDecimal itemPrice) {
        if (agreed != null) {
            return "两个 Agent 经过 " + Math.max(1, rounds) + " 轮协商，建议成交价 ¥"
                    + agreed.toPlainString() + "，等待买卖双方确认。";
        }
        if (timedOut) {
            return "协商超时（超过 " + (aiProperties.getMatchTimeoutMs() / 1000)
                    + " 秒），未达成一致。建议先站内私信沟通，或直接浏览其他在售商品。";
        }
        if ("seller_reject".equals(reason)) {
            return "卖家 Agent 认为出价过低，未达成一致。可以调整预算后重新发起，或私信卖家。";
        }
        if ("buyer_reject".equals(reason)) {
            return "还价超出买家预算，未达成一致。建议提高预算或看看同类其他商品。";
        }
        return "经过 " + Math.max(1, rounds) + " 轮协商仍未达成一致，建议站内私信继续沟通。";
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case MatchRequest.STATUS_NEGOTIATING -> "协商中";
            case MatchRequest.STATUS_AWAITING_CONFIRM -> "待确认";
            case MatchRequest.STATUS_AGREED -> "已达成";
            case MatchRequest.STATUS_FAILED -> "未达成";
            case MatchRequest.STATUS_CANCELLED -> "已取消";
            case MatchRequest.STATUS_EXPIRED -> "已过期";
            case MatchRequest.STATUS_ORDERED -> "已下单";
            default -> "未知";
        };
    }

    private String senderText(String sender) {
        if (MatchMessage.SENDER_BUYER.equals(sender)) {
            return "买家 Agent";
        }
        if (MatchMessage.SENDER_SELLER.equals(sender)) {
            return "卖家 Agent";
        }
        return "系统";
    }

    private String actionText(String action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case MatchMessage.ACTION_OFFER -> "出价";
            case MatchMessage.ACTION_COUNTER -> "还价";
            case MatchMessage.ACTION_ACCEPT -> "接受";
            case MatchMessage.ACTION_REJECT -> "拒绝";
            default -> "说明";
        };
    }

    private String itemTitle(Long itemId) {
        Item item = itemId == null ? null : itemMapper.selectById(itemId);
        return item == null ? "商品" : item.getTitle();
    }

    private String budgetText(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return "未说明";
        }
        return (min == null ? "不限下限" : "¥" + min) + " ~ "
                + (max == null ? "不限上限" : "¥" + max);
    }

    private String safeMessage(String message, String fallback) {
        return clip(StringUtils.hasText(message) ? message : fallback, 300);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    // ================================================================== 内部类型

    private record AgentMove(MatchRuleEngine.Move move, String source, Long logId, boolean modelUsed) {
    }

    private record NegotiationResult(BigDecimal agreedPrice, int rounds, String source,
                                     String summary, Long lastLogId, boolean timedOut) {
        boolean agreed() {
            return agreedPrice != null;
        }
    }
}
