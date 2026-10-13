package com.bobo.domain.vo;

import lombok.Data;

/**
 * 通用展示视图集合。
 * <p>
 * 这些都是"几乎不参与查询、只用于输出"的轻对象，集中放在一个文件里，
 * 避免为 6 个只有字段的类各开一个文件。
 * <p>
 * <b>为什么不用 record：</b>其中 UserVO / ReportVO / ReviewVO / MessageVO 会被
 * MyBatis 直接映射（XML 里配置了 resultMap），而 record 只有 {@code userId()}
 * 这类访问器、没有 {@code setUserId()}，MyBatis 会抛
 * 「There is no setter for property named 'userId'」。
 * 因此统一用普通类 + Lombok {@code @Data} 生成 getter/setter。
 *
 * @author bobo
 */
public final class SimpleVos {

    private SimpleVos() {
    }

    /** 用户公开信息（个人主页、商品详情卖家卡片、后台用户列表） */
    @Data
    public static class UserVO {
        private Long userId;
        private String username;
        private String nickname;
        private String avatar;
        private String campus;
        private String college;
        private String grade;
        private String bio;
        private Integer creditScore;
        private String role;
        private Integer verifyStatus;
        private Integer status;
        private String phone;
        private String email;
        private java.time.LocalDateTime createTime;
    }

    /** 分类（含在售数量，首页分类导航用） */
    @Data
    public static class CategoryVO {
        private Long categoryId;
        private Long parentId;
        private String name;
        private String icon;
        private Integer sort;
        private Integer status;
        private Long itemCount;
    }

    /** 公告 / 通知 */
    @Data
    public static class NoticeVO {
        private Long noticeId;
        private Long userId;
        private String title;
        private String content;
        private String type;
        private Long bizId;
        private Integer isRead;
        private java.time.LocalDateTime createTime;
    }

    /** 举报（后台列表，带举报人与被举报对象摘要） */
    @Data
    public static class ReportVO {
        private Long reportId;
        private Long userId;
        private String reporterNickname;
        private String targetType;
        private Long targetId;
        private String targetTitle;
        private String reason;
        private String detail;
        private Integer status;
        private String handleRemark;
        private String handleByName;
        private java.time.LocalDateTime handleTime;
        private java.time.LocalDateTime createTime;
    }

    /** 评价 */
    @Data
    public static class ReviewVO {
        private Long reviewId;
        private Long orderId;
        private Long itemId;
        private String itemTitle;
        private Long fromId;
        private String fromNickname;
        private String fromAvatar;
        private Long toId;
        private String toNickname;
        private Integer score;
        private String content;
        private String role;
        private Integer status;
        private java.time.LocalDateTime createTime;
    }

    /** 私信消息 */
    @Data
    public static class MessageVO {
        private Long messageId;
        private Long conversationId;
        private Long fromId;
        private String fromNickname;
        private String fromAvatar;
        private Long toId;
        private String content;
        private Integer isRead;
        private java.time.LocalDateTime createTime;
    }
}
