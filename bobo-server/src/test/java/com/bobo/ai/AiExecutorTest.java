package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
import com.bobo.config.props.AiProperties;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.mapper.AiCallLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 执行器测试：验证「模型可用 / 失败 / 被限流 / 未配置」四条路径的行为一致性。
 *
 * <p>这是整个 AI 模块最该有测试的地方——降级逻辑一旦写错，
 * 表现是「AI 偶发返回空白」或「线上一直悄悄走规则却没人发现」，
 * 两者都极难从现象反推，只能在单元测试里钉死。
 *
 * <p>用真实对象 + Mock Mapper，不启 Spring 上下文：
 * 这类逻辑与容器无关，起容器只会让测试慢十倍。
 *
 * @author bobo
 */
class AiExecutorTest {

    private AiProperties properties;
    private AiCallLogMapper callLogMapper;
    private MockChatInvoker mockInvoker;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setApiKey("test-key");
        properties.setModel("deepseek-chat");
        properties.setMaxAttempts(1);
        properties.getRate().setGlobalQps(100);
        properties.getRate().setUserQps(100);
        properties.getRate().setDailyQuota(100);
        callLogMapper = mock(AiCallLogMapper.class);
        when(callLogMapper.countTodayByUser(anyLong())).thenReturn(0);
        when(callLogMapper.insert(any(AiCallLog.class))).thenAnswer(inv -> {
            AiCallLog log = inv.getArgument(0);
            log.setLogId(999L);
            return 1;
        });
        mockInvoker = new MockChatInvoker();
    }

    @AfterEach
    void tearDown() {
        com.bobo.security.AuthContext.clear();
    }

    private AiExecutor executorWith(ChatInvoker invoker) {
        AiAvailability availability = new AiAvailability(properties, invoker);
        AiRateLimiter rateLimiter = new AiRateLimiter(properties);
        AiCallLogWriter writer = new AiCallLogWriter(callLogMapper, properties);
        AiCallRecorder recorder = new AiCallRecorder(writer, properties, invoker);
        return new AiExecutor(properties, availability, rateLimiter, recorder, invoker, callLogMapper);
    }

    private String parseTitle(String raw) {
        return new SafeJsonParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse(raw, node -> node.hasNonNull("title") ? node.get("title").asText() : null);
    }

    @Test
    @DisplayName("模型正常返回时取模型结果，并落一条 success 流水（含 token 与成本）")
    void modelSuccess() {
        mockInvoker.enqueue("{\"title\":\"九成新 iPad Air 4\"}", 420, 160);
        AiExecutor executor = executorWith(mockInvoker);

        AiOutcome<String> outcome = executor.execute(AiCallLog.SCENE_PUBLISH_DRAFT,
                "system", "user", this::parseTitle, () -> "规则标题");

        assertThat(outcome.value()).isEqualTo("九成新 iPad Air 4");
        assertThat(outcome.source()).isEqualTo(AiExecutor.SOURCE_AI);
        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.logId()).isEqualTo(999L);

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(captor.capture());
        AiCallLog log = captor.getValue();
        assertThat(log.getStatus()).isEqualTo(AiCallLog.STATUS_SUCCESS);
        assertThat(log.getScene()).isEqualTo(AiCallLog.SCENE_PUBLISH_DRAFT);
        assertThat(log.getPromptTokens()).isEqualTo(420);
        assertThat(log.getCompletionTokens()).isEqualTo(160);
        assertThat(log.getTotalTokens()).isEqualTo(580);
        // 成本必须 > 0，否则"AI 花了多少钱"这个指标就是假的
        assertThat(log.getEstimatedCost()).isGreaterThan(BigDecimal.ZERO);
        // 只记摘要不记原文：隐私要求
        assertThat(log.getInputHash()).isNotNull();
        assertThat(log.getOutputHash()).isNotNull();
    }

    @Test
    @DisplayName("模型调用失败时降级为规则结果，并且业务不抛异常")
    void modelFailureDegrades() {
        mockInvoker.enqueueFailure(new ChatInvoker.ChatInvokeException("连接超时"));
        AiExecutor executor = executorWith(mockInvoker);

        AiOutcome<String> outcome = executor.execute(AiCallLog.SCENE_PUBLISH_DRAFT,
                "system", "user", this::parseTitle, () -> "规则标题");

        assertThat(outcome.value()).isEqualTo("规则标题");
        assertThat(outcome.source()).isEqualTo(AiExecutor.SOURCE_RULE);
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.degradeNote()).contains("规则");

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(captor.capture());
        AiCallLog log = captor.getValue();
        assertThat(log.getStatus()).isEqualTo(AiCallLog.STATUS_FAILED);
        assertThat(log.getErrorMsg()).contains("连接超时");
    }

    @Test
    @DisplayName("模型返回无法解析的内容时同样降级")
    void unparsableOutputDegrades() {
        mockInvoker.enqueue("抱歉，我不能帮你写商品标题。");
        AiExecutor executor = executorWith(mockInvoker);

        AiOutcome<String> outcome = executor.execute(AiCallLog.SCENE_PUBLISH_DRAFT,
                "system", "user", this::parseTitle, () -> "规则标题");

        assertThat(outcome.value()).isEqualTo("规则标题");
        assertThat(outcome.degraded()).isTrue();
    }

    @Test
    @DisplayName("未配置 api-key 时不调用模型、不写流水，直接返回规则结果")
    void noApiKeyShortCircuits() {
        properties.setApiKey("");
        AiExecutor executor = executorWith(mockInvoker);

        AiOutcome<String> outcome = executor.execute(AiCallLog.SCENE_PUBLISH_DRAFT,
                "system", "user", this::parseTitle, () -> "规则标题");

        assertThat(outcome.value()).isEqualTo("规则标题");
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.degradeNote()).contains("api-key");
        // 关键断言：无 key 时一次模型调用都不该发出，也不该污染流水表
        assertThat(mockInvoker.remaining()).isZero();
        assertThat(mockInvoker.receivedUserPrompts()).isEmpty();
        verify(callLogMapper, never()).insert(any(AiCallLog.class));
    }

    @Test
    @DisplayName("无模型实现（noop）时同样短路，不会抛异常")
    void noopInvokerShortCircuits() {
        mockInvoker.asNoop();
        AiExecutor executor = executorWith(mockInvoker);

        AiOutcome<String> outcome = executor.execute(AiCallLog.SCENE_SEARCH_PARSE,
                "system", "user", this::parseTitle, () -> "规则槽位");

        assertThat(outcome.value()).isEqualTo("规则槽位");
        assertThat(outcome.degraded()).isTrue();
        verify(callLogMapper, never()).insert(any(AiCallLog.class));
    }

    @Test
    @DisplayName("达到每日配额后停止调用模型，降级并给出可展示的原因")
    void dailyQuotaExhausted() {
        properties.getRate().setDailyQuota(3);
        when(callLogMapper.countTodayByUser(anyLong())).thenReturn(3);
        com.bobo.security.AuthContext.set(new com.bobo.security.AuthContext.LoginUser(2L, "bobo", false));
        mockInvoker.enqueue("{\"title\":\"不该被用到\"}");
        AiExecutor executor = executorWith(mockInvoker);

        AiOutcome<String> outcome = executor.execute(AiCallLog.SCENE_PUBLISH_DRAFT,
                "system", "user", this::parseTitle, () -> "规则标题");

        assertThat(outcome.value()).isEqualTo("规则标题");
        assertThat(outcome.degradeNote()).contains("上限");
        // 脚本还剩一条，说明确实没有调用模型
        assertThat(mockInvoker.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("单用户限流生效后降级为规则结果")
    void userRateLimited() {
        properties.getRate().setUserQps(1);
        properties.getRate().setGlobalQps(1);
        com.bobo.security.AuthContext.set(new com.bobo.security.AuthContext.LoginUser(2L, "bobo", false));
        mockInvoker.enqueue("{\"title\":\"第一次\"}").enqueue("{\"title\":\"第二次\"}");
        AiExecutor executor = executorWith(mockInvoker);

        AiOutcome<String> first = executor.execute(AiCallLog.SCENE_PUBLISH_DRAFT,
                "system", "user", this::parseTitle, () -> "规则标题");
        AiOutcome<String> second = executor.execute(AiCallLog.SCENE_PUBLISH_DRAFT,
                "system", "user", this::parseTitle, () -> "规则标题");

        assertThat(first.value()).isEqualTo("第一次");
        assertThat(second.value()).isEqualTo("规则标题");
        assertThat(second.degradeNote()).contains("频繁");
    }

    @Test
    @DisplayName("token 折算成本：按 bobo.ai.pricing 单价计算")
    void costEstimation() {
        properties.getPricing().setInputPerMillion(2.0);
        properties.getPricing().setOutputPerMillion(8.0);

        // 1,000,000 输入 * 2 元 + 1,000,000 输出 * 8 元 = 10 元
        assertThat(AiCallRecorder.estimateCost(properties, 1_000_000, 1_000_000))
                .isEqualByComparingTo(new BigDecimal("10.000000"));
        // 真实量级：420 输入 + 160 输出 ≈ 0.00212 元
        assertThat(AiCallRecorder.estimateCost(properties, 420, 160))
                .isEqualByComparingTo(new BigDecimal("0.002120"));
        // token 缺失时记 0 而不是抛异常（供应商未返回 usage 的兜底）
        assertThat(AiCallRecorder.estimateCost(properties, null, null))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("内容摘要稳定且不泄露原文")
    void contentHash() {
        String hash = AiCallRecorder.shortHash("九成新 iPad Air 4 64G");
        assertThat(hash).hasSize(16);
        assertThat(AiCallRecorder.shortHash("九成新 iPad Air 4 64G")).isEqualTo(hash);
        assertThat(AiCallRecorder.shortHash("另一段内容")).isNotEqualTo(hash);
        assertThat(hash).doesNotContain("iPad");
        assertThat(AiCallRecorder.shortHash(null)).isNull();
    }
}
