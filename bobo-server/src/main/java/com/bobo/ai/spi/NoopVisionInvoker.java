package com.bobo.ai.spi;

import java.util.List;

/**
 * 空视觉实现：没有配置 DeepSeek Key 或显式关闭多模态时使用。
 *
 * <p>它的价值与 {@link NoopChatInvoker} 一样：让「没有视觉模型」成为合法状态，
 * 业务代码不必写 if-null；{@link #available()} 返回 false，降价服务据此走规则兜底。
 *
 * @author bobo
 */
public class NoopVisionInvoker implements VisionInvoker {

    private final String reason;

    public NoopVisionInvoker(String reason) {
        this.reason = reason;
    }

    @Override
    public String name() {
        return "noop-vision";
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public ChatInvoker.ChatReply generate(String systemPrompt, String userPrompt,
                                          List<ImageAttachment> images, int maxTokens) {
        throw new ChatInvoker.ChatInvokeException(reason);
    }
}
