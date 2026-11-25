package com.bobo.ai.match;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.ai.AiItemContextService;
import com.bobo.domain.entity.Category;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.User;
import com.bobo.mapper.CategoryMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 撮合上下文：把 Agent 谈判需要的「事实」一次性准备好。
 *
 * <p>这对应技术规格书 3.2 里的「买家 Agent 工具」：
 * 查询在售商品、获取商品详情、查询同类成交价。本项目<b>没有把工具选择交给模型</b>，
 * 而是由编排层确定性地调用——原因是：
 * <ul>
 *   <li>工具调用轮次不可控：模型可能反复查同一件商品，把 30 秒预算烧光；</li>
 *   <li>查询结果需要可审计：每一步用了哪些事实，服务端能记录、能解释；</li>
 *   <li>本场景的查询集合很小且固定，没有「让模型自己决定查什么」的必要。</li>
 * </ul>
 * 模型仍然负责「用这些事实谈判」，这是它真正擅长的部分。
 *
 * @author bobo
 */
@Service
@RequiredArgsConstructor
public class MatchContextService {

    private static final int COMPARABLE_LIMIT = 5;

    private final ItemMapper itemMapper;
    private final UserMapper userMapper;
    private final CategoryMapper categoryMapper;
    private final AiItemContextService itemContextService;

    /** 一件在售的同类商品（供 Agent 参考行情，也展示给买家） */
    public record Comparable(Long itemId, String title, BigDecimal price,
                             String condition, String cover) {
    }

    /** 一次撮合的全部事实 */
    public record Facts(Long itemId, String title, BigDecimal price, String condition,
                        String categoryName, String sellerNickname, Integer sellerCredit,
                        String campus, String location,
                        BigDecimal budgetMin, BigDecimal budgetMax, String demand,
                        String preferredTime, String preferredPlace,
                        List<Comparable> comparables,
                        AiItemContextService.PriceStats soldStats,
                        AiItemContextService.PriceStats onSaleStats) {
    }

    public Facts collect(Item item, BigDecimal budgetMin, BigDecimal budgetMax,
                         String demand, String preferredTime, String preferredPlace) {
        Category category = item.getCategoryId() == null ? null
                : categoryMapper.selectById(item.getCategoryId());
        String categoryName = category == null ? null : category.getName();

        User seller = userMapper.selectById(item.getSellerId());
        String sellerNickname = seller == null ? "同学" : seller.getNickname();
        Integer sellerCredit = seller == null ? 100 : seller.getCreditScore();

        List<Comparable> comparables = new ArrayList<>();
        if (item.getCategoryId() != null) {
            List<Item> items = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                    .eq(Item::getCategoryId, item.getCategoryId())
                    .eq(Item::getStatus, Item.STATUS_ON_SALE)
                    .ne(Item::getItemId, item.getItemId())
                    .orderByDesc(Item::getCreateTime)
                    .last("LIMIT " + COMPARABLE_LIMIT));
            if (items != null) {
                for (Item other : items) {
                    comparables.add(new Comparable(other.getItemId(), other.getTitle(),
                            other.getPrice(), other.getConditionLevel(), other.getCover()));
                }
            }
        }

        return new Facts(item.getItemId(), item.getTitle(), item.getPrice(),
                item.getConditionLevel(), categoryName, sellerNickname, sellerCredit,
                item.getCampus(), item.getLocation(),
                budgetMin, budgetMax, demand, preferredTime, preferredPlace,
                comparables,
                itemContextService.soldPriceStats(item.getCategoryId(), categoryName, 90),
                itemContextService.priceStats(item.getCategoryId(), categoryName));
    }

    /**
     * 把事实压成提示词里的行情段落。
     * 数字全部来自数据库，模型只读取、不计算——否则它会「顺手」编一个成交价。
     */
    public String marketContext(Facts facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("商品：").append(facts.title()).append('\n');
        sb.append("标价：").append(facts.price()).append(" 元；成色：")
                .append(facts.condition() == null ? "未说明" : facts.condition()).append('\n');
        sb.append("分类：").append(facts.categoryName() == null ? "未分类" : facts.categoryName())
                .append("；卖家信用分：").append(facts.sellerCredit()).append('\n');
        if (facts.campus() != null || facts.location() != null) {
            sb.append("校区/地点：").append(facts.campus() == null ? "-" : facts.campus())
                    .append(" / ").append(facts.location() == null ? "-" : facts.location()).append('\n');
        }

        sb.append("\n同类在售：");
        if (facts.comparables().isEmpty()) {
            sb.append("暂无\n");
        } else {
            sb.append('\n');
            for (Comparable comparable : facts.comparables()) {
                sb.append("- ").append(comparable.title()).append("，¥")
                        .append(comparable.price()).append("，")
                        .append(comparable.condition() == null ? "成色未说明" : comparable.condition())
                        .append('\n');
            }
        }

        if (facts.soldStats() != null) {
            sb.append("近 90 天同类成交：").append(facts.soldStats().basis())
                    .append("，中位价 ¥").append(facts.soldStats().mid()).append('\n');
        } else {
            sb.append("近 90 天同类成交：样本不足\n");
        }
        if (facts.onSaleStats() != null) {
            sb.append("同类在售价位：").append(facts.onSaleStats().basis())
                    .append("，中位价 ¥").append(facts.onSaleStats().mid()).append('\n');
        }
        return sb.toString();
    }

    /** 买家诉求段落（预算、时间、地点） */
    public String demandContext(Facts facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("买家预算：");
        if (facts.budgetMin() == null && facts.budgetMax() == null) {
            sb.append("未说明\n");
        } else {
            sb.append(facts.budgetMin() == null ? "不限下限" : "¥" + facts.budgetMin())
                    .append(" ~ ")
                    .append(facts.budgetMax() == null ? "不限上限" : "¥" + facts.budgetMax())
                    .append('\n');
        }
        sb.append("买家诉求：").append(facts.demand() == null ? "无" : facts.demand()).append('\n');
        if (facts.preferredTime() != null) {
            sb.append("期望时间：").append(facts.preferredTime()).append('\n');
        }
        if (facts.preferredPlace() != null) {
            sb.append("期望地点：").append(facts.preferredPlace()).append('\n');
        }
        return sb.toString();
    }
}
