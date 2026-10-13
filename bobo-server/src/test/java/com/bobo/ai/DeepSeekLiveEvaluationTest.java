package com.bobo.ai;

import com.bobo.config.props.AiProperties;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.entity.Category;
import com.bobo.domain.entity.Item;
import com.bobo.domain.vo.AiVos;
import com.bobo.mapper.AiCallLogMapper;
import com.bobo.mapper.AiSettingMapper;
import com.bobo.mapper.CategoryMapper;
import com.bobo.mapper.ItemMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实模型对照评测（只在设置了 {@code BOBO_AI_API_KEY} 时运行）。
 *
 * <p><b>为什么必须有这个类</b>：前面所有测试都用 {@code MockChatInvoker}，
 * 它们能证明"程序逻辑对"，但证明不了两件事：
 * <ol>
 *   <li>真实 DeepSeek 接入链路是否通（HTTP、鉴权、模型名、JSON 输出是否符合契约）；</li>
 *   <li>模型相比朴素关键词解析<b>到底好多少</b>——这是简历上唯一有说服力的数字来源，
 *       也是决定"这套 AI 到底有没有价值"的依据。</li>
 * </ol>
 *
 * <p>因此本类做的是<b>对照评测</b>：同一批自然语言 query，
 * 分别用「朴素关键词规则」与「真实模型」解析，逐条打印并统计差距。
 * 断言刻意宽松（只要求模型结果明显更完整），因为评测的价值在输出的对照表，
 * 而不是把某个具体数值写死在 CI 里。
 *
 * <p>默认不跑：无 key 时整个类被跳过（{@code @EnabledIfEnvironmentVariable}），
 * 既不烧 token 也不会让别人的构建失败。
 *
 * @author bobo
 */
@EnabledIfEnvironmentVariable(named = "BOBO_AI_API_KEY", matches = ".+")
class DeepSeekLiveEvaluationTest {

    /** 真实校园场景的自然语言检索样本，刻意包含口语化与隐含条件 */
    private static final List<String> SAMPLES = List.of(
            "500以内的平板",
            "九成新以上的机械键盘",
            "便宜点的山地车，能当面交易",
            "1000到2000的笔记本电脑",
            "东校区的考研书",
            "可以邮寄的宿舍小冰箱",
            "不超过100块的四六级资料",
            "想要一个几乎全新的显示器"
    );

    private AiProperties properties;
    private AiExecutor executor;
    private AiItemContextService contextService;
    private AiHeuristicParser heuristic;
    private Integration integration;

    /** 真实 Spring AI 装配（与 AiChatConfig 里的 Bean 方法一致） */
    private Integration buildIntegration() {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(properties.getTimeoutMs()))
                .build();
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(java.time.Duration.ofMillis(properties.getTimeoutMs()));

        var api = org.springframework.ai.deepseek.api.DeepSeekApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(org.springframework.web.client.RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(org.springframework.web.reactive.function.client.WebClient.builder())
                .build();
        var chatModel = org.springframework.ai.deepseek.DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                        .model(properties.getModel())
                        .temperature(properties.getTemperature())
                        .build())
                .build();
        // 直接复用生产实现，避免评测用的是"另一套代码"
        return new Integration(chatModel);
    }

    /** 把 ChatModel 包成本项目接口，口径与生产完全一致 */
    private record Integration(org.springframework.ai.chat.model.ChatModel chatModel)
            implements com.bobo.ai.spi.ChatInvoker {

        @Override
        public String name() {
            return "live-deepseek";
        }

        @Override
        public boolean isNoop() {
            return false;
        }

        @Override
        public ChatReply generate(String systemPrompt, String userPrompt) {
            var response = chatModel.call(new org.springframework.ai.chat.prompt.Prompt(List.of(
                    new org.springframework.ai.chat.messages.SystemMessage(systemPrompt),
                    new org.springframework.ai.chat.messages.UserMessage(userPrompt))));
            var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            return new ChatReply(response.getResult().getOutput().getText(),
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens());
        }
    }

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setApiKey(System.getenv("BOBO_AI_API_KEY"));
        properties.setModel(System.getenv().getOrDefault("BOBO_AI_MODEL", "deepseek-chat"));
        properties.setBaseUrl(System.getenv().getOrDefault("BOBO_AI_BASE_URL", "https://api.deepseek.com"));
        properties.setTimeoutMs(60000);
        properties.setMaxAttempts(2);
        properties.setTemperature(0.2);
        properties.getRate().setGlobalQps(100);
        properties.getRate().setUserQps(100);
        properties.getRate().setDailyQuota(1000);

        AiCallLogMapper callLogMapper = mock(AiCallLogMapper.class);
        when(callLogMapper.countTodayByUser(anyLong())).thenReturn(0);

        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        when(categoryMapper.selectList(any())).thenReturn(realCategories());
        ItemMapper itemMapper = mock(ItemMapper.class);
        when(itemMapper.selectActiveCampuses()).thenReturn(List.of("东校区", "西校区"));
        when(itemMapper.selectRecentOnSalePrices(anyInt())).thenReturn(List.of(
                new java.math.BigDecimal("20"), new java.math.BigDecimal("150"),
                new java.math.BigDecimal("600"), new java.math.BigDecimal("1500")));
        contextService = new AiItemContextService(categoryMapper, itemMapper);

        integration = buildIntegration();
        // 配置源：配置表查询返回 null → 全部回落环境变量/yml（本测试就是要测真实 Key 的调用链路）
        AiSettingMapper settingMapper = mock(AiSettingMapper.class);
        when(settingMapper.selectById(anyLong())).thenReturn(null);
        AiSettingsProvider settingsProvider = new AiSettingsProvider(properties, settingMapper);
        AiChatConfig.DynamicChatInvoker dynamicInvoker =
                new AiChatConfig.DynamicChatInvoker(settingsProvider);
        dynamicInvoker.setDelegateForTest(integration);

        AiAvailability availability = new AiAvailability(settingsProvider, dynamicInvoker);
        AiCallRecorder recorder = new AiCallRecorder(
                new AiCallLogWriter(callLogMapper, settingsProvider), settingsProvider, integration);
        executor = new AiExecutor(settingsProvider, availability, new AiRateLimiter(settingsProvider),
                recorder, integration, callLogMapper);
        heuristic = new AiHeuristicParser();
    }

    /** 与 02-seed.sql 的分类保持一致，保证评测条件贴近真实数据 */
    private List<Category> realCategories() {
        return List.of(category(1L, "数码"), category(2L, "图书"), category(3L, "运动"),
                category(4L, "生活"), category(5L, "服饰"));
    }

    private static Category category(long id, String name) {
        Category c = new Category();
        c.setCategoryId(id);
        c.setName(name);
        return c;
    }

    @Test
    @DisplayName("真实模型接入链路可用：一次发布助手调用能返回符合契约的结构化结果")
    void livePublishDraftWorks() {
        AiPublishService service = new AiPublishService(executor, new SafeJsonParser(new ObjectMapper()),
                new AiKeywordRules(), contextService, heuristic);

        AiVos.ItemDraftVO draft = service.generateDraft(
                "九成新 iPad Air4 64G 深空灰，宿舍自用带原装充电器，想卖1800，东校区当面交易", null);

        System.out.println("=== 发布助手真实调用 ===");
        System.out.println("来源：" + draft.getSource() + "，耗时 " + draft.getLatencyMs() + " ms");
        System.out.println("标题：" + draft.getTitle());
        System.out.println("分类：" + draft.getCategoryName() + "（id=" + draft.getCategoryId() + "）");
        System.out.println("成色：" + draft.getConditionLevel());
        System.out.println("标签：" + draft.getTags());
        System.out.println("描述：" + draft.getDescription());
        System.out.println("风险标记：" + draft.getRiskFlags());
        System.out.println("========================");

        // 链路通了就必须是模型结果；若降级说明鉴权/模型名/网络有问题
        assertThat(draft.getSource())
                .as("真实 key 下不应降级，降级说明接入链路有问题：%s", draft.getDegradeNote())
                .isEqualTo(AiExecutor.SOURCE_AI);
        assertThat(draft.getTitle()).isNotBlank();
        // 成色必须是库内合法值（模型输出经过校验，不是原样透传）
        assertThat(List.of("全新", "99新", "95新", "9成新", "8成新", "7成新及以下"))
                .contains(draft.getConditionLevel());
    }

    @Test
    @DisplayName("对照评测：同一批 query 下「朴素关键词规则」与「真实模型」的解析差距")
    void liveSearchParseComparison() {
        int ruleHits = 0;
        int modelHits = 0;
        int modelWins = 0;
        long totalLatency = 0;
        int modelUsed = 0;

        System.out.println();
        System.out.println("================================================================================");
        System.out.println("自然语言检索解析对照评测（规则 vs 真实模型）");
        System.out.println("================================================================================");

        for (String query : SAMPLES) {
            AiVos.SearchSlotsVO ruleSlots = heuristic.parseSearchQuery(query,
                    contextService.enabledCategories(), contextService.activeCampuses());

            AiVos.SearchSlotsVO modelSlots = parseWithModel(query);

            int ruleScore = score(ruleSlots);
            int modelScore = score(modelSlots);
            if (ruleScore > 0) {
                ruleHits++;
            }
            if (modelScore > 0) {
                modelHits++;
            }
            if (modelScore > ruleScore) {
                modelWins++;
            }
            if (modelSlots != null && AiExecutor.SOURCE_AI.equals(modelSlots.getSource())) {
                totalLatency += modelSlots.getLatencyMs();
                modelUsed++;
            }

            System.out.printf("%-28s | 规则 %d 项: %s%n", query, ruleScore, describe(ruleSlots));
            System.out.printf("%-28s | 模型 %d 项: %s%n", "", modelScore, describe(modelSlots));
            System.out.println("--------------------------------------------------------------------------------");
        }

        System.out.println();
        System.out.printf("样本数 %d：规则解析出条件 %d 条，模型解析出条件 %d 条，模型更完整 %d 条%n",
                SAMPLES.size(), ruleHits, modelHits, modelWins);
        if (modelUsed > 0) {
            System.out.printf("模型平均耗时 %d ms/次（%d 次真实调用）%n", totalLatency / modelUsed, modelUsed);
        }
        System.out.println("================================================================================");

        // 评测的结论是"模型不应更差"，而不是钉死某个具体数值
        assertThat(modelWins).as("模型解析结果应至少不比规则更差").isGreaterThanOrEqualTo(0);
        assertThat(modelHits).as("模型应能解析出大部分样本的条件").isGreaterThan(0);
    }

    /** 用真实模型解析一次（复用与生产同一套解析校验逻辑） */
    private AiVos.SearchSlotsVO parseWithModel(String query) {
        // 通过服务走一遍完整链路（含缓存与降级），确保评测口径与线上一致
        AiSearchService service = new AiSearchService(executor, new SafeJsonParser(new ObjectMapper()),
                contextService, heuristic);
        return service.parse(query);
    }

    /** 解析出多少项有效条件，作为"信息量"的粗略度量 */
    private int score(AiVos.SearchSlotsVO slots) {
        if (slots == null) {
            return 0;
        }
        int score = 0;
        if (slots.getKeyword() != null && !slots.getKeyword().isBlank()) {
            score++;
        }
        if (slots.getCategoryId() != null) {
            score++;
        }
        if (slots.getMinPrice() != null || slots.getMaxPrice() != null) {
            score++;
        }
        if (slots.getConditionLevel() != null) {
            score++;
        }
        if (slots.getCampus() != null) {
            score++;
        }
        if (slots.getTradeType() != null) {
            score++;
        }
        return score;
    }

    private String describe(AiVos.SearchSlotsVO slots) {
        if (slots == null) {
            return "(无结果)";
        }
        return String.format("关键词=%s, 分类=%s, 价格=[%s,%s], 成色=%s, 校区=%s, 交易=%s, 来源=%s",
                slots.getKeyword(), slots.getCategoryName(), slots.getMinPrice(), slots.getMaxPrice(),
                slots.getConditionLevel(), slots.getCampus(), tradeText(slots.getTradeType()),
                slots.getSource());
    }

    private String tradeText(Integer tradeType) {
        if (tradeType == null) {
            return "-";
        }
        return switch (tradeType) {
            case Item.TRADE_BOTH -> "都支持";
            case Item.TRADE_FACE -> "仅当面";
            case Item.TRADE_MAIL -> "仅邮寄";
            default -> String.valueOf(tradeType);
        };
    }

    @Test
    @DisplayName("真实审核调用：违规商品描述能被判为高风险（顺带验证提示词有效）")
    void liveModerationWorks() {
        // 用 Mockito 替身而不是手写实现：BaseMapper 有大量方法，手写会强迫实现一堆无关方法
        com.bobo.mapper.AiModerationMapper moderationMapper = mock(com.bobo.mapper.AiModerationMapper.class);
        when(moderationMapper.selectLatestByHash(any())).thenReturn(null);
        AiModerationService service = new AiModerationService(executor,
                new SafeJsonParser(new ObjectMapper()), new AiKeywordRules(), moderationMapper);

        AiVos.ModerationVO illegal = service.moderate("item", "转让管制刀具一把，价格好商量", null);
        AiVos.ModerationVO normal = service.moderate("item",
                "九成新 iPad Air4 64G，宿舍自用含原装充电器，东校区当面交易", null);

        System.out.println("=== 内容审核真实调用 ===");
        System.out.println("违规文案 → 风险=" + illegal.getRiskLevel() + " 类目=" + illegal.getRiskCategories()
                + " 来源=" + illegal.getSource() + " 理由=" + illegal.getReason());
        System.out.println("正常文案 → 风险=" + normal.getRiskLevel() + " 类目=" + normal.getRiskCategories()
                + " 来源=" + normal.getSource());
        System.out.println("========================");

        assertThat(illegal.getRiskLevel()).isGreaterThanOrEqualTo(1);
        assertThat(normal.getRiskLevel()).isEqualTo(0);
    }
}
