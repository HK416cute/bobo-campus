package com.bobo.ai;

import com.bobo.config.props.AiProperties;
import com.bobo.domain.entity.AiSetting;
import com.bobo.mapper.AiSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置合并逻辑测试：验证「数据库优先、环境变量兜底」这条规则在四种情形下都成立。
 *
 * <p>这是本次「配置不再写死」改动里最该测的地方，因为它的行为是隐式的：
 * <ul>
 *   <li>合并写错 → 管理员改完配置发现不生效（库里有值却被 yml 覆盖），或反过来
 *       「没进过后台的部署」突然失效（yml 不再兜底）；</li>
 *   <li>「清空 Key」的语义写错 → 管理员明明清空了，系统却默默回落到环境变量里的旧 Key，
 *       表现为「怎么还在花钱」——这是最隐蔽的一类问题。</li>
 * </ul>
 *
 * @author bobo
 */
class AiSettingsProviderTest {

    private AiProperties properties;
    private AiSettingMapper settingMapper;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey("env-key-1234567890");
        properties.setBaseUrl("https://env.example.com");
        properties.setModel("env-model");
        properties.setTimeoutMs(15000);
        properties.setMaxAttempts(1);
        properties.setTemperature(0.2);
        properties.getRate().setGlobalQps(10);
        properties.getRate().setUserQps(2);
        properties.getRate().setDailyQuota(200);
        properties.getPricing().setInputPerMillion(2.0);
        properties.getPricing().setOutputPerMillion(8.0);

        settingMapper = mock(AiSettingMapper.class);
    }

    private AiSettingsProvider provider() {
        return new AiSettingsProvider(properties, settingMapper);
    }

    @Test
    @DisplayName("配置表无行时：全部回落环境变量（升级零成本的保证）")
    void fallsBackToEnvironmentWhenNoRow() {
        when(settingMapper.selectById(anyLong())).thenReturn(null);
        AiSettingsProvider.RuntimeSettings settings = provider().current();

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.apiKey()).isEqualTo("env-key-1234567890");
        assertThat(settings.baseUrl()).isEqualTo("https://env.example.com");
        assertThat(settings.model()).isEqualTo("env-model");
        assertThat(settings.timeoutMs()).isEqualTo(15000);
        assertThat(settings.globalQps()).isEqualTo(10);
        assertThat(settings.dailyQuota()).isEqualTo(200);
        assertThat(settings.priceInput()).isEqualByComparingTo(new BigDecimal("2.0"));
        assertThat(settings.usable()).isTrue();
    }

    @Test
    @DisplayName("配置表有值时：以数据库为准（后台改配置立即生效的前提）")
    void databaseWinsOverEnvironment() {
        AiSetting row = new AiSetting();
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setEnabled(1);
        row.setApiKey("db-key-0987654321");
        row.setBaseUrl("https://db.example.com");
        row.setModel("db-model");
        row.setTimeoutMs(30000L);
        row.setMaxAttempts(3);
        row.setTemperatureX100(70);
        row.setGlobalQps(50);
        row.setUserQps(5);
        row.setDailyQuota(999);
        row.setPriceInput(new BigDecimal("1.5"));
        row.setPriceOutput(new BigDecimal("6.0"));
        when(settingMapper.selectById(anyLong())).thenReturn(row);

        AiSettingsProvider.RuntimeSettings settings = provider().current();

        assertThat(settings.apiKey()).isEqualTo("db-key-0987654321");
        assertThat(settings.baseUrl()).isEqualTo("https://db.example.com");
        assertThat(settings.model()).isEqualTo("db-model");
        assertThat(settings.timeoutMs()).isEqualTo(30000);
        assertThat(settings.maxAttempts()).isEqualTo(3);
        assertThat(settings.temperature()).isEqualTo(0.7);
        assertThat(settings.globalQps()).isEqualTo(50);
        assertThat(settings.userQps()).isEqualTo(5);
        assertThat(settings.dailyQuota()).isEqualTo(999);
        assertThat(settings.priceInput()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(settings.priceOutput()).isEqualByComparingTo(new BigDecimal("6.0"));
    }

    @Test
    @DisplayName("库里 key 为空时回落环境变量（保证「配了环境变量就能直接用」）")
    void emptyKeyInDatabaseFallsBackToEnvironment() {
        AiSetting row = new AiSetting();
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setEnabled(1);
        row.setApiKey("");
        when(settingMapper.selectById(anyLong())).thenReturn(row);

        AiSettingsProvider.RuntimeSettings settings = provider().current();

        // 首次部署的常见形态：环境变量里配好了 key，配置表刚建好还没填
        assertThat(settings.apiKey()).isEqualTo("env-key-1234567890");
        assertThat(settings.usable()).isTrue();
    }

    @Test
    @DisplayName("库里 key 为 NULL 时同样回落环境变量")
    void nullKeyInDatabaseFallsBackToEnvironment() {
        AiSetting row = new AiSetting();
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setEnabled(1);
        row.setApiKey(null);
        when(settingMapper.selectById(anyLong())).thenReturn(row);

        assertThat(provider().current().apiKey()).isEqualTo("env-key-1234567890");
    }

    @Test
    @DisplayName("清空 Key 的语义 = 清空 + 关闭总开关（由 Service 保证，避免环境变量偷偷接管）")
    void clearingKeyMustAlsoDisable() {
        AiSetting row = new AiSetting();
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setApiKey("");
        // Service 在 clearApiKey=true 时会写 enabled=0，这里模拟那个结果
        row.setEnabled(0);
        when(settingMapper.selectById(anyLong())).thenReturn(row);

        AiSettingsProvider.RuntimeSettings settings = provider().current();

        // 即使环境变量里有 key，总开关关闭 → 必须判为不可用
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.usable()).isFalse();
    }

    @Test
    @DisplayName("总开关关闭时即使有 Key 也不可用")
    void disabledIsNotUsable() {
        AiSetting row = new AiSetting();
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setEnabled(0);
        row.setApiKey("db-key-0987654321");
        when(settingMapper.selectById(anyLong())).thenReturn(row);

        AiSettingsProvider.RuntimeSettings settings = provider().current();

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.usable()).isFalse();
    }

    @Test
    @DisplayName("库行里部分字段为 NULL 时：逐字段回落环境变量（而不是整行失效）")
    void partialRowFallsBackPerField() {
        AiSetting row = new AiSetting();
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setEnabled(1);
        row.setApiKey("db-key-0987654321");
        // 其余字段全为 null，应逐项回落 yml
        when(settingMapper.selectById(anyLong())).thenReturn(row);

        AiSettingsProvider.RuntimeSettings settings = provider().current();

        assertThat(settings.apiKey()).isEqualTo("db-key-0987654321");
        assertThat(settings.baseUrl()).isEqualTo("https://env.example.com");
        assertThat(settings.model()).isEqualTo("env-model");
        assertThat(settings.timeoutMs()).isEqualTo(15000);
        assertThat(settings.globalQps()).isEqualTo(10);
    }

    @Test
    @DisplayName("配置表不可用（未执行建表脚本）时：不抛异常，回落环境变量并给出可展示的告警")
    void storageFailureDegradesGracefully() {
        when(settingMapper.selectById(anyLong()))
                .thenThrow(new RuntimeException("Table 'bobo.bobo_ai_setting' doesn't exist"));

        AiSettingsProvider p = provider();
        AiSettingsProvider.RuntimeSettings settings = p.current();

        assertThat(settings.apiKey()).isEqualTo("env-key-1234567890");
        assertThat(settings.usable()).isTrue();
        assertThat(p.storageWarning()).contains("05-ai-setting.sql");
    }

    @Test
    @DisplayName("reload() 后读到新值（保存配置后刷新生效的机制）")
    void reloadPicksUpNewValue() {
        when(settingMapper.selectById(anyLong())).thenReturn(null);
        AiSettingsProvider p = provider();
        assertThat(p.current().model()).isEqualTo("env-model");

        AiSetting row = new AiSetting();
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setModel("changed-model");
        when(settingMapper.selectById(anyLong())).thenReturn(row);

        assertThat(p.current().model()).as("未 reload 时应仍是旧快照").isEqualTo("env-model");
        p.reload();
        assertThat(p.current().model()).isEqualTo("changed-model");
    }

    @Test
    @DisplayName("Key 脱敏：长 Key 保留首6末4，短 Key 全星号（脱敏后不得暴露原文）")
    void apiKeyMasking() {
        assertThat(AiSettingsService.mask("sk-709a395f34ca4b9587da10262027407e"))
                .isEqualTo("sk-709****407e");
        // 短 key 保留首尾等于泄露，必须全遮
        assertThat(AiSettingsService.mask("sk-short")).isEqualTo("********");
        assertThat(AiSettingsService.mask("")).isEmpty();
        assertThat(AiSettingsService.mask(null)).isEmpty();
    }
}
