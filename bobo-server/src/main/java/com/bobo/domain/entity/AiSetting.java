package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 运行配置（单行表，{@code setting_id} 恒为 1）。
 *
 * <p><b>为什么要有这张表</b>：原先 api-key / model 等全靠环境变量注入，
 * 而环境变量只在容器启动时读取一次——管理员改完必须重启容器才生效，
 * 这在"演示/临时换模型/换 key"的场景里体验很差。落库之后可以在后台直接改，
 * 由 {@code AiSettingsProvider} 热重建模型客户端，**不重启进程**。
 *
 * <p><b>为什么不复用 {@code bobo.ai.*} 配置</b>：yml/环境变量仍是"出厂默认值"，
 * 本表是"运行期覆盖值"。两者是 fallback 关系，不是替代关系——
 * 这样即使用户从没进过后台配置页，环境变量依然能正常工作（向后兼容）。
 *
 * <p><b>加密说明</b>：{@code api_key} 目前以明文存储。理由与边界：
 * <ul>
 *   <li>这是单实例校园项目，数据库与应用同机同网，加密密钥同样要存在某处，
 *       在"密钥与密文同库"的前提下加密只是增加复杂度而不增加实际安全性；</li>
 *   <li>真正的防线做在别处：接口<b>永远不返回明文</b>（只回脱敏值）、
 *       日志不打印 key、响应里只给「是否已配置」的布尔位。</li>
 * </ul>
 * 若将来要上多租户或跨机部署，再引入 KMS/环境主密钥做信封加密即可，
 * 届时只需改本表的读写两处。
 *
 * @author bobo
 */
@Data
@TableName("bobo_ai_setting")
public class AiSetting implements Serializable {

    /** 固定主键：本表只存一行配置 */
    public static final long SINGLETON_ID = 1L;

    @TableId(value = "setting_id")
    private Long settingId;

    /** 总开关 */
    private Integer enabled;

    /** 模型服务 Key。空 = 未配置，走规则降级 */
    private String apiKey;

    private String baseUrl;
    private String model;

    /** 单次调用超时（毫秒） */
    private Long timeoutMs;

    /** 失败重试次数 */
    private Integer maxAttempts;

    /** 采样温度（100 = 1.00，避免浮点在库里出现精度噪音） */
    private Integer temperatureX100;

    private Integer globalQps;
    private Integer userQps;
    private Integer dailyQuota;

    /** 元/百万 token，仅用于成本估算 */
    private java.math.BigDecimal priceInput;
    private java.math.BigDecimal priceOutput;

    /** 最后修改人 */
    private Long updateBy;

    private LocalDateTime updateTime;
}
