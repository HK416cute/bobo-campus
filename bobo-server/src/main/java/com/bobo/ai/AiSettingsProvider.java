package com.bobo.ai;

import com.bobo.config.props.AiProperties;
import com.bobo.domain.entity.AiSetting;
import com.bobo.mapper.AiSettingMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 配置源：**数据库优先、环境变量兜底**。
 *
 * <p>这是「配置不再写死」的核心。所有 AI 组件（门控、限流、执行器、模型客户端）
 * 都通过本类读取配置，而不是直接读 {@link AiProperties}。
 *
 * <p>三条设计要点：
 * <ol>
 *   <li><b>兜底而不是替代</b>：数据库里没有这一行、或某个字段为空时，回落到
 *       {@code bobo.ai.*}（yml / 环境变量）。好处是**升级零成本**——
 *       从没进过后台配置页的部署，行为与以前完全一致；</li>
 *   <li><b>进程内缓存</b>：每次生成提示词都查一次数据库没有意义。配置读进
 *       {@code volatile} 快照，写操作后刷新，读路径无锁；</li>
 *   <li><b>表不存在也不崩</b>：没执行 {@code 05-ai-setting.sql} 的旧库会抛异常，
 *       这里捕获后记 warn 并返回纯环境变量配置——AI 功能照旧可用，
 *       只是后台配置页改不动（并在页面上给出提示）。</li>
 * </ol>
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSettingsProvider {

    private final AiProperties properties;
    private final AiSettingMapper settingMapper;

    /** 当前生效配置快照，读路径无锁 */
    private volatile RuntimeSettings snapshot;

    /** 库表不可用（未建表等）时置位，供后台页面提示 */
    private volatile String storageWarning;

    /**
     * 当前生效的运行时配置。首次访问时加载，之后走缓存。
     */
    public RuntimeSettings current() {
        RuntimeSettings local = snapshot;
        if (local == null) {
            synchronized (this) {
                local = snapshot;
                if (local == null) {
                    local = load();
                    snapshot = local;
                }
            }
        }
        return local;
    }

    /** 重新从数据库加载（保存配置、或运维手动刷新后调用） */
    public RuntimeSettings reload() {
        RuntimeSettings loaded = load();
        snapshot = loaded;
        return loaded;
    }

    public String storageWarning() {
        return storageWarning;
    }

    /** 读取数据库行，与 yml 默认值合并 */
    private RuntimeSettings load() {
        AiSetting row = null;
        try {
            row = settingMapper.selectById(AiSetting.SINGLETON_ID);
            storageWarning = null;
        } catch (Exception e) {
            // 旧库未执行 05-ai-setting.sql 时会走到这里：降级为纯环境变量配置，不影响 AI 可用性
            storageWarning = "AI 配置表不可用（是否已执行 mysql/init/05-ai-setting.sql？）：" + e.getMessage();
            log.warn(storageWarning);
        }
        return merge(row);
    }

    /**
     * 合并规则：数据库有值就用数据库，没值用 yml/环境变量。
     *
     * <p><b>关于 apiKey 的取舍（改过一次，记录原因）</b>：
     * 最初实现为"库里有行就以库里为准，哪怕为空也不回落"——理由是避免
     * 「管理员清空了 key，系统却偷偷用回环境变量里的旧 key」。
     * 但实测发现这会让**首次部署**很别扭：环境变量里明明配好了 key，
     * 建完配置表后 AI 反而变不可用，管理员必须再手填一遍。
     *
     * <p>现在的规则是：<b>库里为空 → 回落环境变量</b>；清空动作由
     * {@code AiSettingsService} 同时把「总开关」关掉来表达，语义无歧义：
     * 开关关闭时可用性判定先失败，根本不会走到环境变量那条路。
     * 这样既保住了「配了环境变量就能直接用」，也不会出现「清空了却还在花钱」。
     */
    private RuntimeSettings merge(AiSetting row) {
        AiProperties.Rate rate = properties.getRate();
        AiProperties.Pricing pricing = properties.getPricing();

        boolean enabled = row != null && row.getEnabled() != null
                ? row.getEnabled() == 1
                : properties.isEnabled();

        // 库里有非空 key 用它，否则回落环境变量（见上面的取舍说明）
        String apiKey = row != null && StringUtils.hasText(row.getApiKey())
                ? row.getApiKey().trim() : properties.getApiKey();

        String baseUrl = row != null && StringUtils.hasText(row.getBaseUrl())
                ? row.getBaseUrl().trim() : properties.getBaseUrl();
        String model = row != null && StringUtils.hasText(row.getModel())
                ? row.getModel().trim() : properties.getModel();
        long timeoutMs = row != null && row.getTimeoutMs() != null && row.getTimeoutMs() > 0
                ? row.getTimeoutMs() : properties.getTimeoutMs();
        int maxAttempts = row != null && row.getMaxAttempts() != null && row.getMaxAttempts() > 0
                ? row.getMaxAttempts() : properties.getMaxAttempts();
        double temperature = row != null && row.getTemperatureX100() != null
                ? row.getTemperatureX100() / 100.0 : properties.getTemperature();
        int globalQps = row != null && row.getGlobalQps() != null && row.getGlobalQps() > 0
                ? row.getGlobalQps() : rate.getGlobalQps();
        int userQps = row != null && row.getUserQps() != null && row.getUserQps() > 0
                ? row.getUserQps() : rate.getUserQps();
        int dailyQuota = row != null && row.getDailyQuota() != null && row.getDailyQuota() > 0
                ? row.getDailyQuota() : rate.getDailyQuota();
        BigDecimal priceIn = row != null && row.getPriceInput() != null
                ? row.getPriceInput() : BigDecimal.valueOf(pricing.getInputPerMillion());
        BigDecimal priceOut = row != null && row.getPriceOutput() != null
                ? row.getPriceOutput() : BigDecimal.valueOf(pricing.getOutputPerMillion());

        return new RuntimeSettings(enabled, apiKey, baseUrl, model, timeoutMs, maxAttempts,
                temperature, globalQps, userQps, dailyQuota, priceIn, priceOut);
    }

    /** 读数据库原始行（后台表单回显用；没有行时返回 null） */
    public AiSetting rawRow() {
        try {
            return settingMapper.selectById(AiSetting.SINGLETON_ID);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写入配置（整行覆盖语义由调用方保证），随后刷新缓存。
     * 使用 upsert 语义：行不存在则插入，存在则更新。
     */
    public void save(AiSetting row) {
        row.setSettingId(AiSetting.SINGLETON_ID);
        row.setUpdateBy(AuthContext.userIdOrNull());
        row.setUpdateTime(LocalDateTime.now());
        AiSetting existing = settingMapper.selectById(AiSetting.SINGLETON_ID);
        if (existing == null) {
            settingMapper.insert(row);
        } else {
            settingMapper.updateById(row);
        }
        reload();
        log.info("AI 配置已由用户 {} 更新：enabled={}, model={}, apiKey={}",
                row.getUpdateBy(), row.getEnabled(), row.getModel(),
                StringUtils.hasText(row.getApiKey()) ? "已设置" : "未设置");
    }

    /**
     * 运行时配置快照（不可变）。字段顺序与 {@link #merge} 保持一致。
     *
     * @param enabled         总开关
     * @param apiKey          模型 Key（可能为空）
     * @param baseUrl         服务地址
     * @param model           模型名
     * @param timeoutMs       单次超时
     * @param maxAttempts     重试次数
     * @param temperature     采样温度
     * @param globalQps       全局限流
     * @param userQps         单用户限流
     * @param dailyQuota      单用户每日配额
     * @param priceInput      输入 token 单价（元/百万）
     * @param priceOutput     输出 token 单价（元/百万）
     */
    public record RuntimeSettings(boolean enabled, String apiKey, String baseUrl, String model,
                                  long timeoutMs, int maxAttempts, double temperature,
                                  int globalQps, int userQps, int dailyQuota,
                                  BigDecimal priceInput, BigDecimal priceOutput) {

        /** 是否具备调用模型的必要条件（开关开 + key 非空） */
        public boolean usable() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }
    }
}
