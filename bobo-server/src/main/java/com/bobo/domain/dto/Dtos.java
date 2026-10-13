package com.bobo.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 请求体 DTO 集合。
 * <p>
 * 这里全部使用 record：不可变、字段即构造参数、Jackson 反序列化无需 setter，
 * 配合 jakarta.validation 注解即可在 Controller 上用 @Valid 校验。
 * 集中在一个文件里是为了避免十几个只有字段的小文件把目录撑散。
 *
 * @author bobo
 */
public final class Dtos {

    private Dtos() {
    }

    // ------------------------------------------------------------------ 认证

    @Schema(description = "注册请求")
    public record RegisterDTO(
            @Schema(description = "登录账号（学号 / 手机号）", example = "2025010101")
            @NotBlank(message = "请输入账号")
            @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "账号需为 4-32 位字母、数字或下划线")
            String username,

            @Schema(description = "密码", example = "bobo123456")
            @NotBlank(message = "请输入密码")
            @Size(min = 6, max = 32, message = "密码长度需为 6-32 位")
            String password,

            @Schema(description = "昵称", example = "小鱼干")
            @NotBlank(message = "请输入昵称")
            @Size(max = 20, message = "昵称最长 20 个字符")
            String nickname,

            @Schema(description = "手机号")
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone,

            @Schema(description = "邮箱")
            @Email(message = "邮箱格式不正确")
            String email,

            @Schema(description = "校区", example = "东校区")
            String campus,

            @Schema(description = "学院")
            String college,

            @Schema(description = "年级", example = "2023")
            String grade,

            @Schema(description = "学号")
            String studentNo,

            @Schema(description = "管理员邀请码，仅创建管理员账号时需要")
            String adminCode
    ) {
    }

    @Schema(description = "登录请求")
    public record LoginDTO(
            @Schema(description = "登录账号") @NotBlank(message = "请输入账号") String username,
            @Schema(description = "密码") @NotBlank(message = "请输入密码") String password
    ) {
    }

    @Schema(description = "修改密码")
    public record ChangePasswordDTO(
            @NotBlank(message = "请输入原密码") String oldPassword,
            @NotBlank(message = "请输入新密码")
            @Size(min = 6, max = 32, message = "新密码长度需为 6-32 位") String newPassword
    ) {
    }

    // ------------------------------------------------------------------ 个人资料

    @Schema(description = "更新个人资料")
    public record ProfileDTO(
            @Size(max = 20, message = "昵称最长 20 个字符") String nickname,
            String avatar,
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @Email(message = "邮箱格式不正确") String email,
            String campus,
            String college,
            String grade,
            String studentNo,
            @Size(max = 60, message = "个性签名最长 60 个字符") String bio
    ) {
    }

    @Schema(description = "提交学生认证")
    public record VerifyDTO(
            @NotBlank(message = "请填写学号") String studentNo,
            @NotBlank(message = "请填写学院") String college
    ) {
    }

    // ------------------------------------------------------------------ 商品

    @Schema(description = "发布 / 编辑商品")
    public record ItemDTO(
            @NotBlank(message = "请填写标题")
            @Size(max = 60, message = "标题最长 60 个字符")
            String title,

            @Size(max = 2000, message = "描述最长 2000 个字符")
            String description,

            @NotNull(message = "请填写价格")
            @DecimalMin(value = "0.01", message = "价格必须大于 0")
            BigDecimal price,

            BigDecimal originalPrice,

            @NotNull(message = "请选择分类")
            Long categoryId,

            String conditionLevel,

            @Schema(description = "交易方式：0 都支持 / 1 仅当面 / 2 仅邮寄")
            Integer tradeType,

            String campus,
            String location,

            @Schema(description = "图片URL列表，第一张作为封面")
            List<String> images
    ) {
    }

    @Schema(description = "商品搜索条件（复杂筛选也支持 POST，便于前端传数组参数）")
    public record ItemSearchDTO(
            String keyword,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String campus,
            String conditionLevel,
            Integer tradeType,
            String sortBy
    ) {
    }

    // ------------------------------------------------------------------ 订单

    @Schema(description = "创建订单")
    public record OrderCreateDTO(
            @NotNull(message = "缺少商品ID") Long itemId,

            @Schema(description = "交易方式：1 当面交易 / 2 邮寄")
            Integer tradeType,

            @Schema(description = "收货地址ID，邮寄时必填") Long addressId,

            @Schema(description = "当面交易地点") String tradePlace,

            @Schema(description = "约定交易时间") LocalDateTime tradeTime,

            @Size(max = 200, message = "备注最长 200 个字符") String remark
    ) {
    }

    @Schema(description = "订单操作（发货 / 收货 / 取消 / 退款）")
    public record OrderActionDTO(
            @Schema(description = "取消或退款原因") String reason,
            @Schema(description = "快递公司（发货时）") String logisticsCompany,
            @Schema(description = "快递单号（发货时）") String logisticsNo
    ) {
    }

    // ------------------------------------------------------------------ 评价

    @Schema(description = "提交评价")
    public record ReviewDTO(
            @NotNull(message = "缺少订单ID") Long orderId,
            @NotNull(message = "请打分")
            @Min(value = 1, message = "评分最低 1 分")
            @Max(value = 5, message = "评分最高 5 分")
            Integer score,
            @Size(max = 500, message = "评价最长 500 个字符") String content
    ) {
    }

    // ------------------------------------------------------------------ 地址

    @Schema(description = "新增 / 编辑收货地址")
    public record AddressDTO(
            @NotBlank(message = "请填写收货人") String receiver,
            @NotBlank(message = "请填写联系电话")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            String campus,
            @NotBlank(message = "请填写详细地址") String detail,
            Integer isDefault
    ) {
    }

    // ------------------------------------------------------------------ 私信

    @Schema(description = "发送私信")
    public record MessageDTO(
            @NotNull(message = "缺少会话ID") Long conversationId,
            @NotBlank(message = "消息不能为空")
            @Size(max = 500, message = "消息最长 500 个字符") String content
    ) {
    }

    @Schema(description = "发起会话（从商品详情点「聊一聊」）")
    public record ConversationOpenDTO(
            @NotNull(message = "缺少商品ID") Long itemId,
            @Size(max = 500, message = "消息最长 500 个字符") String content
    ) {
    }

    // ------------------------------------------------------------------ 举报

    @Schema(description = "提交举报")
    public record ReportDTO(
            @NotBlank(message = "请选择举报对象类型") String targetType,
            @NotNull(message = "缺少举报对象ID") Long targetId,
            @NotBlank(message = "请选择举报原因") String reason,
            @Size(max = 500, message = "补充说明最长 500 个字符") String detail
    ) {
    }

    // ------------------------------------------------------------------ 管理端

    @Schema(description = "商品审核")
    public record AuditDTO(
            @NotNull(message = "请给出审核结果") Boolean pass,
            @Size(max = 200, message = "驳回原因最长 200 个字符") String reason
    ) {
    }

    @Schema(description = "批量商品操作")
    public record BatchIdsDTO(
            @NotEmpty(message = "请选择要操作的数据")
            List<Long> ids
    ) {
    }

    @Schema(description = "分类维护")
    public record CategoryDTO(
            Long categoryId,
            Long parentId,
            @NotBlank(message = "请填写分类名称") @Size(max = 16, message = "分类名称最长 16 个字符") String name,
            String icon,
            Integer sort,
            Integer status
    ) {
    }

    @Schema(description = "用户管理操作")
    public record UserManageDTO(
            Integer status,
            Integer verifyStatus,
            Integer creditDelta,
            String reason
    ) {
    }

    @Schema(description = "举报处理")
    public record ReportHandleDTO(
            @NotNull(message = "请给出处理结果") Boolean handled,
            @Size(max = 200, message = "处理说明最长 200 个字符") String remark,
            @Schema(description = "是否同时下架被举报商品") Boolean offShelfItem
    ) {
    }

    @Schema(description = "发布公告")
    public record NoticeDTO(
            @NotBlank(message = "请填写标题") @Size(max = 60, message = "标题最长 60 个字符") String title,
            @Size(max = 500, message = "内容最长 500 个字符") String content,
            @Schema(description = "接收用户ID，0 表示全站公告") Long userId,
            String type
    ) {
    }

    // ------------------------------------------------------------------ AI

    @Schema(description = "AI 发布助手：把卖家的一句话/草稿补全为结构化建议")
    public record AiDraftDTO(
            @Schema(description = "卖家输入的草稿文本", example = "九成新 iPad Air4 64G 深空灰 带原装充电器 想卖1800")
            @NotBlank(message = "请先输入商品描述")
            @Size(max = 1000, message = "描述最长 1000 个字符")
            String text,

            @Schema(description = "卖家已选分类ID，可空；给了就优先尊重卖家的选择")
            Long categoryId
    ) {
    }

    @Schema(description = "AI 检索意图解析：自然语言 → 结构化检索槽位")
    public record AiSearchParseDTO(
            @Schema(description = "自然语言检索词", example = "500 以内能当面交易的山地车")
            @NotBlank(message = "请输入检索内容")
            @Size(max = 200, message = "检索内容最长 200 个字符")
            String query
    ) {
    }

    @Schema(description = "AI 建议采纳反馈（采纳率指标的采集入口）")
    public record AiFeedbackDTO(
            @Schema(description = "建议类型：publish_draft / search_query")
            @NotBlank(message = "缺少建议类型")
            String suggestionType,

            @Schema(description = "是否采纳")
            @NotNull(message = "缺少是否采纳")
            Boolean accepted,

            @Schema(description = "建议值（截断存储，最长 500）") @Size(max = 500) String suggestedValue,
            @Schema(description = "用户最终提交的值") @Size(max = 500) String finalValue,
            @Schema(description = "归属对象ID（商品/订单等）") Long targetId,
            @Schema(description = "关联的调用流水ID，用于回溯是哪次模型输出") Long logId
    ) {
    }

    @Schema(description = "AI 内容风险预判")
    public record AiModerateDTO(
            @Schema(description = "对象类型：item / review / message")
            @NotBlank(message = "缺少对象类型") String targetType,

            @Schema(description = "待审核内容") @NotBlank(message = "缺少待审核内容")
            @Size(max = 2000, message = "内容最长 2000 个字符") String content,

            @Schema(description = "对象ID，发布前预审时可空") Long targetId
    ) {
    }

    @Schema(description = "运营 Copilot 只读问答")
    public record AiAskDTO(
            @Schema(description = "问题", example = "最近七天哪个分类的退款最多")
            @NotBlank(message = "请输入问题")
            @Size(max = 200, message = "问题最长 200 个字符") String question
    ) {
    }
}
