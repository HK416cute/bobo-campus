package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用流水。
 *
 * <p>这张表是「AI 可观测性」的落点，直接支撑三件事：
 * <ol>
 *   <li><b>成本</b>：按场景统计 token 用量与估算费用（{@code estimated_cost}），
 *       能回答「一次发布平均花多少钱」；</li>
 *   <li><b>稳定性</b>：{@code status}（成功/降级/失败）+ {@code latency_ms} 给出各场景成功率与耗时分位，
 *       是 P99 指标的唯一来源；</li>
 *   <li><b>取证</b>：{@code input_hash}/{@code output_hash} 只存摘要不存原文，
 *       既能做幂等与缓存去重，又避免把用户隐私正文沉到日志表里。</li>
 * </ol>
 *
 * @author bobo
 */
@Data
@TableName("bobo_ai_call_log")
public class AiCallLog implements Serializable {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_DEGRADED = "degraded";
    public static final String STATUS_FAILED = "failed";

    public static final String SCENE_PUBLISH_DRAFT = "publish_draft";
    public static final String SCENE_SEARCH_PARSE = "search_parse";
    public static final String SCENE_SEARCH_RERANK = "search_rerank";
    public static final String SCENE_MODERATION = "moderation";
    public static final String SCENE_ADMIN_REPORT = "admin_report";
    public static final String SCENE_ADMIN_ASK = "admin_ask";

    // --- 技术升级新增场景：三个模块各自独立统计成功率/成本，便于回答“钱花在哪” ---
    /** 视觉定价与成色评估（DeepSeek 多模态） */
    public static final String SCENE_PRICE_ESTIMATE = "price_estimate";
    /** 撮合中的买家 Agent 出价/还价 */
    public static final String SCENE_MATCH_BUYER = "match_buyer";
    /** 撮合中的卖家 Agent 响应 */
    public static final String SCENE_MATCH_SELLER = "match_seller";
    /** RAG 知识库问答 */
    public static final String SCENE_RAG_QA = "rag_qa";

    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;

    private String scene;
    private Long userId;
    private String provider;
    private String model;

    /** 输入摘要（如提示词段落哈希），不落原文 */
    private String inputHash;
    /** 输出摘要，用于缓存命中判定与重复建议识别 */
    private String outputHash;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    private Long latencyMs;

    /** success 成功 / degraded 降级为规则 / failed 失败 */
    private String status;

    /** 降级或失败原因，直接展示给运维看 */
    private String degradeReason;
    private String errorMsg;

    private BigDecimal estimatedCost;

    private LocalDateTime createTime;
}
