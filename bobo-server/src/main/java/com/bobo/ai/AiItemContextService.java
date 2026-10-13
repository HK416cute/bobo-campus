package com.bobo.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.domain.entity.Category;
import com.bobo.domain.entity.Item;
import com.bobo.mapper.CategoryMapper;
import com.bobo.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 为 AI 场景提供<b>数据库侧的事实</b>：分类清单与同类商品价格分位。
 *
 * <p>这是本项目 AI 设计里最重要的一条纪律：<b>数字必须来自数据库，模型只负责语言</b>。
 * 让大模型"估算二手 iPad 大概值多少"得到的是一段听起来很合理的话，
 * 而价格建议是要给用户当锚点的——猜错比不给更糟。因此：
 * <ul>
 *   <li>价格建议由库内同类在售商品的真实分位算出（低=25 分位 / 中=中位数 / 高=75 分位），
 *       并带回样本量，样本不足时明确返回 {@code null} 而不是编造；</li>
 *   <li>分类清单作为提示词的候选集传给模型，模型只能在真实存在的分类里选，
 *       从源头消灭"模型返回一个不存在的分类ID"这类脏数据。</li>
 * </ul>
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiItemContextService {

    /** 价格统计最多取多少条样本：够算分位，又不会把大分类整表捞出来 */
    private static final int PRICE_SAMPLE_LIMIT = 200;

    /** 样本少于这个数就不给价格建议（分位数在太小样本上没有意义） */
    private static final int MIN_SAMPLE_COUNT = 3;

    private final CategoryMapper categoryMapper;
    private final ItemMapper itemMapper;

    /** 启用中的分类（按 sort 排序），供提示词候选集与前端下拉使用 */
    public List<Category> enabledCategories() {
        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .eq(Category::getStatus, Category.STATUS_ENABLED)
                .orderByAsc(Category::getSort));
    }

    /** 当前有在售商品的校区，作为检索解析的候选集（防止模型编出不存在的校区） */
    public List<String> activeCampuses() {
        List<String> campuses = itemMapper.selectActiveCampuses();
        return campuses == null ? List.of() : campuses;
    }

    /**
     * 统计某分类在售商品的价格分位。
     *
     * @param categoryId 分类ID，null 或样本不足时返回 null
     */
    public PriceStats priceStats(Long categoryId, String categoryName) {
        if (categoryId == null) {
            return null;
        }
        List<BigDecimal> prices = sortedCopy(itemMapper.selectOnSalePrices(categoryId, PRICE_SAMPLE_LIMIT));
        if (prices.size() < MIN_SAMPLE_COUNT) {
            return null;
        }
        BigDecimal low = percentile(prices, 0.25);
        BigDecimal mid = percentile(prices, 0.50);
        BigDecimal high = percentile(prices, 0.75);
        String basis = "分类「" + (categoryName == null ? categoryId : categoryName) + "」在售 "
                + prices.size() + " 件";
        return new PriceStats(low, mid, high, prices.size(), basis);
    }

    /** 最近在售商品的价格，用于给检索解析提供"这个平台大概什么价位"的印象 */
    public PriceStats overallPriceStats() {
        List<BigDecimal> prices = sortedCopy(itemMapper.selectRecentOnSalePrices(PRICE_SAMPLE_LIMIT));
        if (prices.size() < MIN_SAMPLE_COUNT) {
            return null;
        }
        return new PriceStats(percentile(prices, 0.25), percentile(prices, 0.50),
                percentile(prices, 0.75), prices.size(), "全站在售 " + prices.size() + " 件");
    }

    /**
     * 取出可排序的副本。
     *
     * <p><b>不能直接对 Mapper 返回的 List 调 sort</b>：Mapper 或测试桩可能返回
     * {@code List.of(...)} 这类不可变列表，直接排序会抛
     * {@code UnsupportedOperationException}——而且只在特定数据源下才暴露，
     * 属于典型的"测试里好好的、上了某个环境就 500"。
     */
    private List<BigDecimal> sortedCopy(List<BigDecimal> source) {
        if (source == null || source.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        List<BigDecimal> copy = new java.util.ArrayList<>(source);
        copy.sort(BigDecimal::compareTo);
        return copy;
    }

    /**
     * 最近邻分位数（nearest-rank）。
     * 用最近邻而不是线性插值：二手价格是离散的真实报价，
     * "插值出来的中间价"在库里并不存在，反而会给出一个谁也买不到的价位。
     */
    private BigDecimal percentile(List<BigDecimal> sorted, double ratio) {
        int index = (int) Math.round(ratio * (sorted.size() - 1));
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 价格统计结果。
     *
     * @param low         25 分位
     * @param mid         中位数（给用户的主建议价）
     * @param high        75 分位
     * @param sampleCount 样本量
     * @param basis       样本来源说明，可直接展示给用户
     */
    public record PriceStats(BigDecimal low, BigDecimal mid, BigDecimal high,
                             int sampleCount, String basis) {
    }
}
