package com.bobo.ai.spi;

/**
 * 对「发一次对话请求」这件事的最小抽象。
 *
 * <p>刻意抽这一层的原因：
 * <ul>
 *   <li>业务场景（发布助手 / 检索解析 / 内容审核）只依赖本接口，<b>不依赖 Spring AI 类型</b>，
 *       于是单元测试可以塞一个 {@code MockChatInvoker} 而不烧 token、不需要外网；</li>
 *   <li>没有 API key 时容器里注入的是 {@link NoopChatInvoker}，业务代码不必写 if-null 判断；</li>
 *   <li>将来换模型厂商（通义 / 智谱 / 自建网关）只新增一个实现类。</li>
 * </ul>
 *
 * @author bobo
 */
public interface ChatInvoker {

    /** 实现名，用于日志与调用流水（如 spring-ai-deepseek / noop） */
    String name();

    /**
     * 是否为「空实现」。空实现表示模型不可用，调用方必须走规则降级。
     *
     * @return true 表示不可用
     */
    boolean isNoop();

    /**
     * 发起一次对话调用。
     *
     * @param systemPrompt 系统提示词，定义角色与输出约束
     * @param userPrompt   用户提示词，承载本次任务的具体输入
     * @return 模型回复（文本 + token 用量），结构化抽取场景下文本应是 JSON
     * @throws ChatInvokeException 调用失败（网络、鉴权、限流、超时等），由上层决定是否降级
     */
    ChatReply generate(String systemPrompt, String userPrompt);

    /**
     * 模型回复。带上 token 用量是因为「成本」必须可算：
     * 只记文本的话，事后只能靠字数拍脑袋估费用。
     *
     * @param text             回复文本
     * @param promptTokens     输入 token 数，模型未返回用量时为 null
     * @param completionTokens 输出 token 数，模型未返回用量时为 null
     */
    record ChatReply(String text, Integer promptTokens, Integer completionTokens) {

        /** 无用量信息的回复（Mock、或供应商未返回 usage 时使用） */
        public static ChatReply of(String text) {
            return new ChatReply(text, null, null);
        }
    }

    /** 调用失败。继承 RuntimeException，避免污染业务方法签名 */
    class ChatInvokeException extends RuntimeException {
        public ChatInvokeException(String message) {
            super(message);
        }

        public ChatInvokeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
