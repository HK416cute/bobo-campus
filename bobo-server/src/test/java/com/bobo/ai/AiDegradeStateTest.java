package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「四态降级」端到端测试：把发布助手与检索解析在四种模型状态下各跑一遍。
 *
 * <p>为什么这四个状态必须逐一验证：它们决定了"模型出问题时用户看到什么"。
 * 尤其是第 4 态（模型返回的字段越界）——只做 JSON 能解析就采信，
 * 会把不存在的分类ID、非法的成色值写进表单，用户提交后才在服务端报错，
 * 表现为"AI 填的东西一提交就失败"。这类脏数据必须在解析层就拦掉。
 *
 * <p>同时验证一条硬约束：<b>未配置 api-key 时一次模型调用都不能发生</b>，
 * 否则"没 key 也能跑"就成了自欺（会一路发请求到鉴权失败）。
 *
 * @author bobo
 */
class AiDegradeStateTest {

    private AiProperties properties;
    private AiCallLogMapper callLogMapper;
    private CategoryMapper categoryMapper;
    private ItemMapper itemMapper;
    private MockChatInvoker invoker;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setApiKey("test-key");
        properties.setModel("deepseek-chat");
        properties.setMaxAttempts(1);
        properties.getRate().setGlobalQps(1000);
        properties.getRate().setUserQps(1000);
        properties.getRate().setDailyQuota(1000);

        callLogMapper = mock(AiCallLogMapper.class);
        when(callLogMapper.countTodayByUser(anyLong())).thenReturn(0);
        when(callLogMapper.insert(any(AiCallLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiCallLog.class).setLogId(77L);
            return 1;
        });

        categoryMapper = mock(CategoryMapper.class);
        Category digital = new Category();
        digital.setCategoryId(1L);
        digital.setName("数码");
        Category book = new Category();
        book.setCategoryId(2L);
        book.setName("图书");
        when(categoryMapper.selectList(any())).thenReturn(List.of(digital, book));

        itemMapper = mock(ItemMapper.class);
        // 价格样本：100 / 200 / 300 / 400 / 500 → 中位数 300
        when(itemMapper.selectOnSalePrices(anyLong(), anyInt())).thenReturn(List.of(
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"),
                new BigDecimal("400"), new BigDecimal("500")));
        when(itemMapper.selectRecentOnSalePrices(anyInt())).thenReturn(List.of(
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"),
                new BigDecimal("400"), new BigDecimal("500")));
        when(itemMapper.selectActiveCampuses()).thenReturn(List.of("东校区", "西校区"));

        invoker = new MockChatInvoker();
    }

    @AfterEach
    void tearDown() {
        com.bobo.security.AuthContext.clear();
    }

    private AiItemContextService contextService() {
        return new AiItemContextService(categoryMapper, itemMapper);
    }

    /**
     * 配置源：配置表查询返回 null，所有值回落 {@link AiProperties}，
     * 等价于"管理员还没进过后台配置页"的部署形态（配置合并逻辑另有单测覆盖）。
     *
     * <p><b>一个测试内必须共享同一实例</b>：provider 缓存快照，
     * 若门控与执行器各拿一个新实例，改配置后会读不到（假故障）。生产里它是单例。
     */
    private AiSettingsProvider sharedProvider;

    private AiSettingsProvider provider() {
        if (sharedProvider == null) {
            AiSettingMapper settingMapper = mock(AiSettingMapper.class);
            when(settingMapper.selectById(anyLong())).thenReturn(null);
            sharedProvider = new AiSettingsProvider(properties, settingMapper);
        }
        return sharedProvider;
    }

    /** 改了 properties 之后同步快照（等价于生产里保存配置后的 reload） */
    private void reloadProvider() {
        provider().reload();
    }

    /** 门控用的动态调用器：用注入点塞入替身，避免真建 Spring AI 客户端 */
    private AiChatConfig.DynamicChatInvoker dynamicInvoker(ChatInvoker chatInvoker) {
        AiChatConfig.DynamicChatInvoker dynamic =
                new AiChatConfig.DynamicChatInvoker(provider());
        dynamic.setDelegateForTest(chatInvoker);
        return dynamic;
    }

    private AiExecutor executorFor(ChatInvoker chatInvoker) {
        AiSettingsProvider provider = provider();
        return new AiExecutor(provider,
                new AiAvailability(provider, dynamicInvoker(chatInvoker)),
                new AiRateLimiter(provider),
                new AiCallRecorder(new AiCallLogWriter(callLogMapper, provider), provider, chatInvoker),
                chatInvoker, callLogMapper);
    }

    private AiPublishService publishService(ChatInvoker chatInvoker) {
        AiExecutor executor = executorFor(chatInvoker);
        return new AiPublishService(executor, new SafeJsonParser(new ObjectMapper()),
                new AiKeywordRules(), contextService(), new AiHeuristicParser());
    }

    private AiSearchService searchService(ChatInvoker chatInvoker) {
        AiExecutor executor = executorFor(chatInvoker);
        return new AiSearchService(executor, new SafeJsonParser(new ObjectMapper()),
                contextService(), new AiHeuristicParser());
    }

    // ------------------------------------------------------------------ 状态 1：未配置 key

    @Test
    @DisplayName("状态1 未配置 api-key：不调用模型、不写流水，规则结果可用并标注降级原因")
    void state1NoApiKey() {
        properties.setApiKey("");
        reloadProvider();
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("九成新 iPad Air4 64G 含原装充电器 可小刀", null);

        assertThat(draft.getSource()).isEqualTo(AiExecutor.SOURCE_RULE);
        assertThat(draft.getDegradeNote()).contains("API Key");
        assertThat(draft.getTitle()).isEqualTo("九成新 iPad Air4 64G 含原装充电器 可小刀");
        assertThat(draft.getTags()).contains("可小刀", "含配件");
        // 硬约束：一次模型调用都不该发出
        assertThat(invoker.receivedUserPrompts()).isEmpty();
        verify(callLogMapper, never()).insert(any(AiCallLog.class));
    }

    @Test
    @DisplayName("状态1 未配置 key 时检索解析仍可用（价格与分类都能解析出来）")
    void state1SearchStillWorks() {
        properties.setApiKey("");
        reloadProvider();
        AiSearchService service = searchService(invoker);

        AiVos.SearchSlotsVO slots = service.parse("东校区 500以内的数码产品");

        assertThat(slots.getSource()).isEqualTo(AiExecutor.SOURCE_RULE);
        assertThat(slots.getMaxPrice()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(slots.getCategoryId()).isEqualTo(1L);
        assertThat(slots.getCampus()).isEqualTo("东校区");
        assertThat(invoker.receivedUserPrompts()).isEmpty();
    }

    // ------------------------------------------------------------------ 状态 2：模型正常

    @Test
    @DisplayName("状态2 模型正常：采用模型结果，来源为 ai，并落 success 流水")
    void state2ModelSuccess() {
        invoker.enqueue("""
                {"title":"九成新 iPad Air4 64G 深空灰","description":"宿舍自用，含原装充电器",
                 "categoryId":1,"conditionLevel":"95新","tags":["含配件","可小刀"],"riskFlags":[]}
                """, 420, 160);
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("iPad Air4 想出掉", null);

        assertThat(draft.getSource()).isEqualTo(AiExecutor.SOURCE_AI);
        assertThat(draft.getTitle()).isEqualTo("九成新 iPad Air4 64G 深空灰");
        assertThat(draft.getCategoryName()).isEqualTo("数码");
        assertThat(draft.getTags()).contains("含配件", "可小刀");
        // 未降级：degradeNote 必须为空，否则前端会同时显示"AI 生成"和降级提示
        assertThat(draft.getDegradeNote()).isNull();
        assertThat(draft.getLogId()).isEqualTo(77L);

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiCallLog.STATUS_SUCCESS);
        assertThat(captor.getValue().getPromptTokens()).isEqualTo(420);
    }

    @Test
    @DisplayName("状态2 价格建议来自数据库真实分位，与模型输出无关")
    void state2PriceFromDatabase() {
        invoker.enqueue("{\"title\":\"平板\",\"categoryId\":1,\"conditionLevel\":\"95新\"}", 100, 50);
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("一个平板", 1L);

        assertThat(draft.getPriceSuggestion()).isNotNull();
        // 样本 100..500，最近邻 25/50/75 分位 = 200 / 300 / 400
        assertThat(draft.getPriceSuggestion().getMid()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(draft.getPriceSuggestion().getLow()).isEqualByComparingTo(new BigDecimal("200"));
        assertThat(draft.getPriceSuggestion().getHigh()).isEqualByComparingTo(new BigDecimal("400"));
        assertThat(draft.getPriceSuggestion().getSampleCount()).isEqualTo(5);
        assertThat(draft.getPriceSuggestion().getBasis()).contains("数码");
    }

    @Test
    @DisplayName("状态2 样本不足（<3 件）时不给价格建议，而不是编一个数")
    void state2InsufficientSamples() {
        when(itemMapper.selectOnSalePrices(anyLong(), anyInt()))
                .thenReturn(List.of(new BigDecimal("100")));
        invoker.enqueue("{\"title\":\"平板\",\"categoryId\":1}", 100, 50);
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("一个平板", 1L);

        assertThat(draft.getPriceSuggestion()).isNull();
    }

    // ------------------------------------------------------------------ 状态 3：输出不可解析

    @Test
    @DisplayName("状态3 模型输出无法解析：降级为规则结果并落 failed 流水")
    void state3UnparsableOutput() {
        invoker.enqueue("抱歉，我不能帮你写商品标题。");
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("九成新 iPad 可小刀", null);

        assertThat(draft.getSource()).isEqualTo(AiExecutor.SOURCE_RULE);
        assertThat(draft.getDegradeNote()).contains("规则");
        assertThat(draft.getTitle()).isEqualTo("九成新 iPad 可小刀");

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiCallLog.STATUS_FAILED);
    }

    @Test
    @DisplayName("状态3 模型返回 JSON 但没有标题，同样判为不可用并降级")
    void state3MissingTitle() {
        invoker.enqueue("{\"description\":\"没有标题\",\"categoryId\":1}");
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("一个平板 可小刀", null);

        assertThat(draft.getSource()).isEqualTo(AiExecutor.SOURCE_RULE);
        assertThat(draft.getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("状态3 检索解析字段全空时降级，不返回什么都不筛的空槽位")
    void state3SearchAllEmpty() {
        invoker.enqueue("{\"keyword\":null,\"categoryId\":null,\"priceMin\":null,\"priceMax\":null,\"campus\":null}");
        AiSearchService service = searchService(invoker);

        AiVos.SearchSlotsVO slots = service.parse("随便看看");

        assertThat(slots.getSource()).isEqualTo(AiExecutor.SOURCE_RULE);
        assertThat(slots.getExplanation()).contains("规则解析");
    }

    // ------------------------------------------------------------------ 状态 4：字段越界（脏数据）

    @Test
    @DisplayName("状态4 模型给出不存在的分类ID与非法成色：分类被丢弃、成色回落默认值")
    void state4OutOfRangeFields() {
        invoker.enqueue("""
                {"title":"九成新平板","categoryId":9999,"conditionLevel":"像新的一样","tags":["含配件"]}
                """, 200, 80);
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("一个平板", null);

        // 采用模型结果，但越界字段必须被清理
        assertThat(draft.getSource()).isEqualTo(AiExecutor.SOURCE_AI);
        assertThat(draft.getCategoryId()).isNull();
        assertThat(draft.getCategoryName()).isNull();
        assertThat(draft.getConditionLevel()).isEqualTo("95新");
        assertThat(draft.getTitle()).isEqualTo("九成新平板");
    }

    @Test
    @DisplayName("状态4 检索解析给出越界分类、未知校区、写反的价格区间都会被纠正")
    void state4SearchSanitize() {
        invoker.enqueue("""
                {"keyword":"平板","categoryId":8888,"priceMin":2000,"priceMax":500,
                 "campus":"南校区","tradeType":9,"sortBy":"whatever"}
                """, 300, 90);
        AiSearchService service = searchService(invoker);

        AiVos.SearchSlotsVO slots = service.parse("随便看看平板");

        assertThat(slots.getSource()).isEqualTo(AiExecutor.SOURCE_AI);
        assertThat(slots.getCategoryId()).isNull();
        assertThat(slots.getCampus()).isNull();
        // 上下限写反要被纠正，否则 SQL 查出来恒空（用户只会觉得"搜不到"）
        assertThat(slots.getMinPrice()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(slots.getMaxPrice()).isEqualByComparingTo(new BigDecimal("2000"));
        // 非法枚举回落安全默认值
        assertThat(slots.getTradeType()).isNull();
        assertThat(slots.getSortBy()).isEqualTo("new");
    }

    @Test
    @DisplayName("状态4 模型返回的 riskFlags 与本地规则取并集，两个口径不互相覆盖")
    void state4RiskFlagsMerged() {
        invoker.enqueue("""
                {"title":"代写论文服务","categoryId":1,"conditionLevel":"95新","riskFlags":["学术作弊"]}
                """, 200, 60);
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("代写论文 包过", null);

        assertThat(draft.getRiskFlags()).contains("学术作弊");
    }

    @Test
    @DisplayName("模型返回的越界 tags 会被裁剪，避免超长标签进入表单")
    void state4TagClipping() {
        invoker.enqueue("""
                {"title":"平板","categoryId":1,"conditionLevel":"95新",
                 "tags":["这是一个非常非常长的标签内容需要被截断","a","b","c","d","e","f","g"]}
                """, 100, 40);
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("一个平板", null);

        assertThat(draft.getTags()).hasSizeLessThanOrEqualTo(6);
        assertThat(draft.getTags()).allSatisfy(tag -> assertThat(tag.length()).isLessThanOrEqualTo(8));
    }

    @Test
    @DisplayName("发布兜底也必须带风险标记：模型不可用时违规内容仍会被标出")
    void fallbackKeepsRiskFlags() {
        properties.setApiKey("");
        reloadProvider();
        AiPublishService service = publishService(invoker);

        AiVos.ItemDraftVO draft = service.generateDraft("出售管制刀具", null);

        assertThat(draft.getSource()).isEqualTo(AiExecutor.SOURCE_RULE);
        assertThat(draft.getRiskFlags()).isNotEmpty();
    }

    @Test
    @DisplayName("Item.TRADE_* 常量与检索槽位语义一致（防契约漂移）")
    void tradeTypeConstants() {
        assertThat(Item.TRADE_BOTH).isZero();
        assertThat(Item.TRADE_FACE).isEqualTo(1);
        assertThat(Item.TRADE_MAIL).isEqualTo(2);
    }
}
