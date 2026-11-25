package com.bobo.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术升级三大模块的视图对象：智能定价、RAG 问答、智能撮合。
 *
 * <p>与 {@code AiVos} 一样，每个结果都带 {@code source} 与 {@code degradeNote}：
 * 用户有权知道这条建议是模型给的还是规则推的，线上排查也能一眼区分
 * 「模型变笨」与「降级生效」。
 *
 * @author bobo
 */
public final class AiUpgradeVos {

    private AiUpgradeVos() {
    }

    // ==================================================================== 定价

    /** 视觉定价与成色评估结果 */
    @Data
    public static class PriceEstimateVO {
        private Long estimateId;
        /** 评估成色，枚举与商品成色一致 */
        private String conditionGrade;
        private BigDecimal priceLow;
        /** 建议价（区间中值） */
        private BigDecimal priceMid;
        private BigDecimal priceHigh;
        /** 0~1，规则兜底时通常偏低 */
        private BigDecimal confidence;
        /** 可解释理由，展示给卖家 */
        private List<String> reasons = new ArrayList<>();
        private int sampleCount;
        /** 样本来源说明，如「同类在售 12 件 / 近 90 天成交 5 笔」 */
        private String basis;
        /** model / rule */
        private String source;
        private String degradeNote;
        private Long logId;
        private long latencyMs;
        private int imageCount;
        /** 卖家是否已采纳 */
        private boolean adopted;
        private LocalDateTime createTime;
    }

    // ==================================================================== RAG

    /** 知识库问答结果 */
    @Data
    public static class RagAnswerVO {
        /** 回答正文；无命中时为「不在知识库范围」 */
        private String answer;
        /** 引用片段，按回答里的 [R1] 编号对应 */
        private List<CitationVO> citations = new ArrayList<>();
        /** 是否在知识库范围内（false 表示拒答，而不是编造） */
        private boolean inScope;
        private int hitCount;
        /** model / rule */
        private String source;
        private String degradeNote;
        private Long logId;
        private long latencyMs;
    }

    /** 一条可核验的引用 */
    @Data
    public static class CitationVO {
        /** 展示编号，如 R1 */
        private String ref;
        private Long docId;
        private String docTitle;
        private String category;
        private Integer chunkNo;
        /** 片段摘要，用于前端展开核对 */
        private String snippet;
        /** 相似度得分，便于调试召回质量 */
        private double score;
    }

    /** 后台知识库状态 */
    @Data
    public static class KnowledgeStatsVO {
        private long docCount;
        private long chunkCount;
        private int dim;
        private LocalDateTime lastIndexTime;
        /** 知识库表不可用（未执行 06-ai-upgrade.sql）时的提示 */
        private String warning;
        private List<KnowledgeDocVO> docs = new ArrayList<>();
    }

    @Data
    public static class KnowledgeDocVO {
        private Long docId;
        private String title;
        private String category;
        private Integer status;
        private Integer chunkCount;
        private Integer contentLength;
        private LocalDateTime updateTime;
    }

    // ==================================================================== 撮合

    /** 撮合单列表项 */
    @Data
    public static class MatchVO {
        private Long matchId;
        private Long itemId;
        private String itemTitle;
        private String itemCover;
        private BigDecimal itemPrice;
        private Long buyerId;
        private Long sellerId;
        private String buyerNickname;
        private String sellerNickname;
        private Integer status;
        private String statusText;
        private BigDecimal agreedPrice;
        private Integer rounds;
        private String summary;
        private boolean buyerConfirmed;
        private boolean sellerConfirmed;
        /** 当前用户角色：buyer / seller / admin */
        private String myRole;
        /** 当前用户是否还需要点确认 */
        private boolean awaitingMyConfirm;
        /** 是否可按撮合价下单（买家视角且已双方确认） */
        private boolean canPlaceOrder;
        private String lastMessage;
        private String source;
        private LocalDateTime expireTime;
        private LocalDateTime createTime;
    }

    /** 撮合详情：带完整协商过程 */
    @Data
    public static class MatchDetailVO extends MatchVO {
        private BigDecimal budgetMin;
        private BigDecimal budgetMax;
        private String demandText;
        private String preferredTime;
        private String preferredPlace;
        private Integer tradeType;
        private String buyerNote;
        private String sellerNote;
        private List<MatchMessageVO> messages = new ArrayList<>();
    }

    /** 协商消息 */
    @Data
    public static class MatchMessageVO {
        private Long messageId;
        private Integer roundNo;
        /** buyer_agent / seller_agent / system */
        private String sender;
        /** 中文展示名：买家 Agent / 卖家 Agent / 系统 */
        private String senderText;
        private String action;
        private String actionText;
        private BigDecimal price;
        private String content;
        private String source;
        private LocalDateTime createTime;
    }

    // ==================================================================== 总览

    /** AI 能力总览：前端据此决定展示哪些入口、禁用哪些按钮 */
    @Data
    public static class FeatureStatusVO {
        /** DeepSeek 文本模型是否可用（发布助手 / 检索 / 运营） */
        private boolean textAvailable;
        private String textModel;
        /** DeepSeek 多模态是否可用（智能定价） */
        private boolean visionAvailable;
        private String visionModel;
        private String visionReason;
        /** 知识库是否已建索引 */
        private boolean ragReady;
        private long ragChunkCount;
        /** 撮合是否可用（文本模型可用时是模型协商，无模型时是规则协商） */
        private boolean matchAvailable;
        private String matchMode;
        /** 每日配额 / 单用户限流，前端提示用 */
        private int dailyQuota;
        private int userQps;
    }

    /** 后台「技术升级」统计卡片 */
    @Data
    public static class UpgradeStatsVO {
        private long estimateTotal;
        private long estimateAdopted;
        private long matchTotal;
        private long matchAgreed;
        private long knowledgeDocCount;
        private long knowledgeChunkCount;
        private boolean visionAvailable;
        private String visionModel;
        private LocalDateTime updateTime;
    }
}
