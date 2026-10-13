package com.bobo.ai.spi;

/**
 * 空实现：没有配置 API key、或显式关闭 AI 时注入这个 Bean。
 *
 * <p>它的存在让「未配置模型」成为一种合法状态而不是错误码——
 * 业务代码照常注入 {@link ChatInvoker}，只需在调用前问一句 {@link #isNoop()}。
 *
 * @author bobo
 */
public class NoopChatInvoker implements ChatInvoker {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public boolean isNoop() {
        return true;
    }

    @Override
    public ChatReply generate(String systemPrompt, String userPrompt) {
        throw new ChatInvokeException("未配置 AI 模型（无 api-key 或已关闭），不应走到实际调用");
    }
}
