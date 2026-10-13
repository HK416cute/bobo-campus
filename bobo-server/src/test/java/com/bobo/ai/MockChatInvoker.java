package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 测试用假模型：按预置脚本返回结果，不联网、不烧 token、结果确定。
 *
 * <p>为什么必须有它：AI 功能最容易写成「只能连真模型才能验证」，
 * 于是回归测试既慢又不稳定（模型输出本身有随机性），最后没人跑测试。
 * 有了这个类，<b>解析容错、降级路径、JSON 契约、场景编排</b>全都能在 CI 里确定性验证，
 * 真实模型只用来做离线评测。
 *
 * <p>用法：
 * <pre>
 * MockChatInvoker mock = new MockChatInvoker()
 *         .enqueue("```json\n{\"title\":\"九成新 iPad\"}\n```")        // 带围栏，验证容错
 *         .enqueueFailure(new ChatInvoker.ChatInvokeException("超时"))  // 验证降级
 *         .enqueue("{\"title\":\"x\"}", 420, 160);                     // 带 token 用量
 * </pre>
 *
 * @author bobo
 */
public class MockChatInvoker implements ChatInvoker {

    /** 队列元素：回复文本（可带 token 用量）或待抛出的异常 */
    private record Scripted(String text, Integer promptTokens, Integer completionTokens,
                            RuntimeException failure) {

        static Scripted text(String text) {
            return new Scripted(text, null, null, null);
        }

        static Scripted text(String text, int promptTokens, int completionTokens) {
            return new Scripted(text, promptTokens, completionTokens, null);
        }

        static Scripted failure(RuntimeException failure) {
            return new Scripted(null, null, null, failure);
        }
    }

    private final Deque<Scripted> script = new ArrayDeque<>();
    private final List<String> receivedUserPrompts = new ArrayList<>();
    private final List<String> receivedSystemPrompts = new ArrayList<>();
    private boolean noop = false;

    /** 预置一次纯文本回复 */
    public MockChatInvoker enqueue(String text) {
        script.addLast(Scripted.text(text));
        return this;
    }

    /** 预置一次带 token 用量的回复（用于验证成本统计） */
    public MockChatInvoker enqueue(String text, int promptTokens, int completionTokens) {
        script.addLast(Scripted.text(text, promptTokens, completionTokens));
        return this;
    }

    /** 预置一次失败（用于验证降级路径） */
    public MockChatInvoker enqueueFailure(RuntimeException failure) {
        script.addLast(Scripted.failure(failure));
        return this;
    }

    /** 把本实例伪装成「无模型」，用于验证门控分支 */
    public MockChatInvoker asNoop() {
        this.noop = true;
        return this;
    }

    /** 已接收的用户提示词，用于断言「提示词里带上了分类清单」这类规则 */
    public List<String> receivedUserPrompts() {
        return receivedUserPrompts;
    }

    public List<String> receivedSystemPrompts() {
        return receivedSystemPrompts;
    }

    /** 尚未被消费的脚本条数，用于断言「没有发生多余调用」（例如缓存命中就不该再调模型） */
    public int remaining() {
        return script.size();
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean isNoop() {
        return noop;
    }

    @Override
    public ChatReply generate(String systemPrompt, String userPrompt) {
        receivedSystemPrompts.add(systemPrompt);
        receivedUserPrompts.add(userPrompt);
        if (script.isEmpty()) {
            throw new ChatInvokeException("MockChatInvoker 脚本已耗尽：测试未预置足够的回复");
        }
        Scripted next = script.pollFirst();
        if (next.failure() != null) {
            throw next.failure();
        }
        return new ChatReply(next.text(), next.promptTokens(), next.completionTokens());
    }
}
