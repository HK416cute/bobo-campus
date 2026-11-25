package com.bobo.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 能力配置项（对应 application.yml 的 bobo.ai.*）。
 *
 * <p>设计前提：<b>AI 是可选增强，不是启动依赖</b>。{@code enabled=false} 或 {@code apiKey} 为空时，
 * {@code com.bobo.ai} 全层自动降级为规则实现，应用照常启动、现有 92 个接口行为不变。
 * 这样本机无 key 也能跑通全部业务流程，CI 也能跑测试（配合 MockChatInvoker）。
 *
 * @author bobo
 */
@Data
@ConfigurationProperties(prefix = "bobo.ai")
public class AiProperties {

    /** 总开关。关闭后所有 AI 场景走规则实现，且不再发起任何外部调用 */
    private boolean enabled = true;

    /** DeepSeek API Key。为空视为未配置，自动降级（不抛异常） */
    private String apiKey;

    /** 接口地址，默认 DeepSeek 官方；换代理/自建网关只改这里 */
    private String baseUrl = "https://api.deepseek.com";

    /** 对话模型：deepseek-flash（通用且支持图像输入）/ deepseek-v4-pro（推理更强） */
    private String model = "deepseek-flash";

    // ------------------------------------------------------------------
    // 多模态与智能撮合（技术升级）：全部走 DeepSeek 同一套 Key / Base URL，
    // 不引入第二家厂商；deepseek-flash 已原生支持图片输入，无需单独的视觉 Key。
    // ------------------------------------------------------------------

    /** 是否启用图像理解（关闭后定价只走规则，不发送图片） */
    private boolean visionEnabled = true;

    /** 多模态模型；留空则复用 {@link #model} */
    private String visionModel = "deepseek-flash";

    /** 单次视觉调用最大输出 token */
    private int visionMaxTokens = 900;

    /** 单次视觉调用的连接/读取超时（毫秒）。看图比纯文本慢，单独给更宽的超时 */
    private long visionTimeoutMs = 30000;

    /** 多轮撮合最大轮次（技术规格书建议 3 轮） */
    private int matchMaxRounds = 3;

    /** 一次撮合协商的端到端时间预算（毫秒），超时不再发起新的模型调用 */
    private long matchTimeoutMs = 30000;

    /** 单次调用超时（毫秒）。超时即走降级，不能让用户干等 */
    private long timeoutMs = 15000;

    /** 单次调用失败后的内部重试次数（不含降级那一跳） */
    private int maxAttempts = 1;

    /** 采样温度：结构化抽取要稳定，所以默认调低 */
    private double temperature = 0.2;

    private Rate rate = new Rate();
    private Pricing pricing = new Pricing();

    /** 限流与配额：防止刷接口把 token 烧穿 */
    @Data
    public static class Rate {
        /** 全局每秒最大请求数 */
        private int globalQps = 10;
        /** 单用户每秒最大请求数 */
        private int userQps = 2;
        /** 单用户每日调用配额（按成功+降级次数计） */
        private int dailyQuota = 200;
    }

    /**
     * 计价（元 / 百万 token），仅用于日志里估算成本。
     * 默认值只是量级参考，换模型时按官方价目表改配置，不要改代码。
     */
    @Data
    public static class Pricing {
        private double inputPerMillion = 2.0;
        private double outputPerMillion = 8.0;
    }

    /** 是否具备调用远程模型的必要条件（开关 + key） */
    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** 是否具备图像理解能力（文本模型可用 + 多模态开关） */
    public boolean visionUsable() {
        return usable() && visionEnabled;
    }
}
