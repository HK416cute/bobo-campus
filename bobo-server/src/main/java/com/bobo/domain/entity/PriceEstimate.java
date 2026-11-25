package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 视觉定价评估记录。
 *
 * <p>为什么单独一张表而不是只把结果写回商品：
 * <ol>
 *   <li>发布前就能估价（此时商品还没落库，{@code itemId} 为空）；</li>
 *   <li>保留每次评估的输入快照与模型理由，便于回答「这个建议价是怎么来的」；</li>
 *   <li>{@code adopted} 支撑采纳率统计——模型说得好不好，最终以卖家是否采用为准。</li>
 * </ol>
 *
 * <p>表里存 {@code title}/{@code description} 摘要而不是完整正文：
 * 一是估价只需要这些，二是避免把用户大段描述再复制一份进评估表。
 *
 * @author bobo
 */
@Data
@TableName("bobo_price_estimate")
public class PriceEstimate implements Serializable {

    @TableId(value = "estimate_id", type = IdType.AUTO)
    private Long estimateId;

    private Long userId;
    /** 商品ID；发布前估价时为空 */
    private Long itemId;

    private String title;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String imageUrl;
    private Integer imageCount;
    /** 标题+描述+分类的摘要，用于统计同一段内容被反复评估的情况 */
    private String textHash;

    private String conditionGrade;
    private BigDecimal priceLow;
    private BigDecimal priceMid;
    private BigDecimal priceHigh;
    private BigDecimal confidence;
    /** 评估理由，JSON 数组文本（服务端用 Jackson 解析后返回给前端） */
    private String reasons;

    private Integer sampleCount;
    private String basis;
    /** 结论来源：model 模型 / rule 规则 */
    private String source;
    private Long logId;
    /** 卖家是否采纳：1 是 / 0 否 */
    private Integer adopted;
    private LocalDateTime createTime;
}
