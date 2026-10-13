package com.bobo.ai;

import com.bobo.ai.AiChatConfig.DynamicChatInvoker;
import com.bobo.ai.spi.ChatInvoker;
import com.bobo.domain.entity.AiSetting;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.vo.AiVos;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 配置的后台管理：读写配置、测试连通性、给出可展示的当前状态。
 *
 * <p>三条安全纪律（这是本功能最容易被写错的地方）：
 * <ol>
 *   <li><b>接口永不返回明文 Key</b>。回显一律走 {@link #mask(String)}；
 *       表单留空表示「不修改」，要清空必须显式勾选，避免误存空值把线上 AI 打哑；</li>
 *   <li><b>日志不打印 Key</b>。保存日志只输出「已设置/未设置」；</li>
 *   <li><b>写操作需要管理员</b>。{@code /api/admin/**} 前缀由 JwtInterceptor 统一拦截，
 *       本类不重复校验，但也因此**不能**被挂到非 admin 前缀下。</li>
 * </ol>
 *
 * <p>保存后的生效链路：写库 → {@link AiSettingsProvider#save} 刷新快照
 * → {@link DynamicChatInvoker#reload()} 重建模型客户端 → 清掉检索解析缓存。
 * 全程不重启进程。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSettingsService {

    /** 合理区间，防止把超时填成 0 或把配额填成天文数字 */
    private static final long MIN_TIMEOUT_MS = 1000;
    private static final long MAX_TIMEOUT_MS = 300_000;
    private static final int MAX_ATTEMPTS_LIMIT = 5;
    private static final int MAX_QPS_LIMIT = 1000;
    private static final int MAX_DAILY_QUOTA_LIMIT = 100_000;

    private final AiSettingsProvider settingsProvider;
    private final DynamicChatInvoker chatInvoker;
    private final AiSearchService searchService;
    private final AiAvailability availability;

    /** 读取配置（Key 已脱敏） */
    public AiVos.AiSettingsVO get() {
        AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
        AiSetting row = settingsProvider.rawRow();

        AiVos.AiSettingsVO vo = new AiVos.AiSettingsVO();
        vo.setEnabled(settings.enabled());
        // 关键：只回脱敏值
        vo.setApiKeyMasked(mask(settings.apiKey()));
        vo.setApiKeyConfigured(StringUtils.hasText(settings.apiKey()));
        vo.setBaseUrl(settings.baseUrl());
        vo.setModel(settings.model());
        vo.setTimeoutMs(settings.timeoutMs());
        vo.setMaxAttempts(settings.maxAttempts());
        vo.setTemperature(settings.temperature());
        vo.setGlobalQps(settings.globalQps());
        vo.setUserQps(settings.userQps());
        vo.setDailyQuota(settings.dailyQuota());
        vo.setPriceInput(settings.priceInput());
        vo.setPriceOutput(settings.priceOutput());
        vo.setUpdateTime(row == null || row.getUpdateTime() == null ? null : row.getUpdateTime().toString());

        vo.setAvailable(availability.modelUsable());
        vo.setDegraded(availability.degraded());
        vo.setDegradeReason(availability.unavailableReason());
        vo.setStorageWarning(settingsProvider.storageWarning());
        vo.setClientError(chatInvoker.lastError());
        return vo;
    }

    /**
     * 保存配置。
     *
     * <p>语义约定（等价于"部分更新"）：
     * <ul>
     *   <li>{@code apiKey} 为 null 或空白 → <b>保持原值不变</b>（表单留空即"不改"）；</li>
     *   <li>{@code clearApiKey = true} → 显式清空 Key（用于下线 AI 或换 Key 前先清掉）；</li>
     *   <li>其余字段为 null → 分别回落到「当前值」，避免前端漏传字段就把配置清空。</li>
     * </ul>
     */
    public AiVos.AiSettingsVO update(Dtos.AiSettingsDTO dto) {
        AiSettingsProvider.RuntimeSettings current = settingsProvider.current();
        AiSetting row = settingsProvider.rawRow();
        if (row == null) {
            row = new AiSetting();
        }

        if (dto.enabled() != null) {
            row.setEnabled(Boolean.TRUE.equals(dto.enabled()) ? 1 : 0);
        } else if (row.getEnabled() == null) {
            row.setEnabled(current.enabled() ? 1 : 0);
        }

        // apiKey：留空=不改；显式清空=清空并同时关闭总开关
        if (Boolean.TRUE.equals(dto.clearApiKey())) {
            row.setApiKey("");
            // 关键：清空 Key 必须同时关掉总开关。
            // 因为 provider 的合并规则是"库里为空则回落环境变量"，
            // 只清空 key 会让环境变量里的旧 key 悄悄继续生效（表现为"清空了还在扣费"）。
            // 关闭总开关后可用性判定先失败，语义无歧义；要恢复只需重新填 Key 并打开开关。
            row.setEnabled(0);
        } else if (StringUtils.hasText(dto.apiKey())) {
            // 去掉可能的 "Bearer " 前缀与首尾空白：管理员常从文档里整段复制
            row.setApiKey(normalizeApiKey(dto.apiKey()));
            // 填了新 Key 视为想启用：否则管理员会遇到"填了 Key 但 AI 还是不可用"的困惑
            if (dto.enabled() == null) {
                row.setEnabled(1);
            }
        } else if (row.getApiKey() == null) {
            // 首次保存且没填 Key：保持与"未配置"一致，而不是写 null 造成歧义
            row.setApiKey("");
        }

        if (StringUtils.hasText(dto.baseUrl())) {
            row.setBaseUrl(trimTrailingSlash(dto.baseUrl()));
        } else if (row.getBaseUrl() == null) {
            row.setBaseUrl(current.baseUrl());
        }
        if (StringUtils.hasText(dto.model())) {
            row.setModel(dto.model().trim());
        } else if (row.getModel() == null) {
            row.setModel(current.model());
        }
        if (dto.timeoutMs() != null) {
            row.setTimeoutMs(clamp(dto.timeoutMs(), MIN_TIMEOUT_MS, MAX_TIMEOUT_MS));
        } else if (row.getTimeoutMs() == null) {
            row.setTimeoutMs(current.timeoutMs());
        }
        if (dto.maxAttempts() != null) {
            row.setMaxAttempts((int) clamp(dto.maxAttempts(), 1, MAX_ATTEMPTS_LIMIT));
        } else if (row.getMaxAttempts() == null) {
            row.setMaxAttempts(current.maxAttempts());
        }
        if (dto.temperature() != null) {
            // 0..2 是 DeepSeek/OpenAI 系的取值范围，乘 100 存整数避免浮点精度噪音
            double t = Math.max(0d, Math.min(2d, dto.temperature()));
            row.setTemperatureX100((int) Math.round(t * 100));
        } else if (row.getTemperatureX100() == null) {
            row.setTemperatureX100((int) Math.round(current.temperature() * 100));
        }
        if (dto.globalQps() != null) {
            row.setGlobalQps((int) clamp(dto.globalQps(), 1, MAX_QPS_LIMIT));
        } else if (row.getGlobalQps() == null) {
            row.setGlobalQps(current.globalQps());
        }
        if (dto.userQps() != null) {
            row.setUserQps((int) clamp(dto.userQps(), 1, MAX_QPS_LIMIT));
        } else if (row.getUserQps() == null) {
            row.setUserQps(current.userQps());
        }
        if (dto.dailyQuota() != null) {
            row.setDailyQuota((int) clamp(dto.dailyQuota(), 1, MAX_DAILY_QUOTA_LIMIT));
        } else if (row.getDailyQuota() == null) {
            row.setDailyQuota(current.dailyQuota());
        }
        if (dto.priceInput() != null) {
            row.setPriceInput(dto.priceInput().max(BigDecimal.ZERO));
        } else if (row.getPriceInput() == null) {
            row.setPriceInput(current.priceInput());
        }
        if (dto.priceOutput() != null) {
            row.setPriceOutput(dto.priceOutput().max(BigDecimal.ZERO));
        } else if (row.getPriceOutput() == null) {
            row.setPriceOutput(current.priceOutput());
        }

        settingsProvider.save(row);
        // 重建模型客户端：这是"改完立即生效"的关键一步
        boolean ready = chatInvoker.reload();
        // 分类/校区可能没变，但模型换了，解析缓存里的旧结论不再可信
        searchService.clearCache();
        log.info("AI 配置已更新并生效：ready={}, enabled={}, model={}",
                ready, row.getEnabled(), row.getModel());

        return get();
    }

    /**
     * 测试连通性：用当前配置真发一次最小请求。
     *
     * <p>为什么必须真调一次：填错 Key 或写错 baseUrl 时，静态校验完全看不出来，
     * 只有在第一次业务请求时才会以"降级"的形式暴露——而那时用户已经在用发布助手了。
     * 让管理员在配置页就能立刻验证，可以把问题挡在配置阶段。
     */
    public AiVos.AiTestResultVO test(String modelOverride) {
        AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
        AiVos.AiTestResultVO result = new AiVos.AiTestResultVO();
        result.setModel(StringUtils.hasText(modelOverride) ? modelOverride.trim() : settings.model());

        if (!settings.usable()) {
            result.setSuccess(false);
            result.setMessage(availability.unavailableReason());
            return result;
        }
        if (chatInvoker.isNoop()) {
            // 配置看起来可用但客户端没建起来，先重建一次再判断（管理员可能刚改完配置）
            chatInvoker.reload();
        }
        if (chatInvoker.isNoop()) {
            result.setSuccess(false);
            result.setMessage("模型客户端构建失败：" + chatInvoker.lastError());
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            ChatInvoker.ChatReply reply = chatInvoker.generate(
                    "你是一个连通性测试助手。只需原样回复 OK 两个字符。", "连通性测试，请回复 OK");
            result.setSuccess(true);
            result.setLatencyMs(System.currentTimeMillis() - start);
            result.setReplyPreview(clip(reply.text(), 120));
            result.setMessage("连通正常，模型已可用");
            result.setPromptTokens(reply.promptTokens());
            result.setCompletionTokens(reply.completionTokens());
        } catch (Exception e) {
            result.setSuccess(false);
            result.setLatencyMs(System.currentTimeMillis() - start);
            result.setMessage("调用失败：" + e.getMessage());
        }
        return result;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * Key 脱敏：只保留首 6 位与末 4 位。
     *
     * <p>长度不足 12 位时一律返回全星号——短 key 保留首尾就等于泄露。
     */
    public static String mask(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "";
        }
        String key = apiKey.trim();
        if (key.length() < 12) {
            return "*".repeat(key.length());
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }

    /** 去掉 "Bearer " 前缀与空白：管理员常从文档里整段复制 */
    private String normalizeApiKey(String raw) {
        String key = raw.trim();
        List<String> prefixes = new ArrayList<>(List.of("Bearer ", "bearer ", "Authorization: "));
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) {
                key = key.substring(prefix.length()).trim();
            }
        }
        return key;
    }

    private String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
