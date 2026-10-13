package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户（买家 / 卖家 / 管理员共用，用 role 区分）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_user")
public class User implements Serializable {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";

    /** 账号状态 */
    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_BANNED = 1;

    /** 学生认证状态 */
    public static final int VERIFY_NONE = 0;
    public static final int VERIFY_PENDING = 1;
    public static final int VERIFY_PASSED = 2;
    public static final int VERIFY_REJECTED = 3;

    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    private String username;

    /** 散列后的密码，永不返回给前端 */
    @JsonIgnore
    private String password;

    private String nickname;
    private String avatar;
    private String phone;
    private String email;
    private String campus;
    private String college;
    private String grade;
    private String studentNo;

    /** 个性签名 */
    private String bio;

    private Integer creditScore;

    /** user / admin */
    private String role;

    private Integer verifyStatus;

    /** 0 正常 / 1 封禁 */
    private Integer status;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    @JsonIgnore
    private String loginIp;

    @TableField("login_time")
    private LocalDateTime loginTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @JsonIgnore
    public boolean isAdminUser() {
        return ROLE_ADMIN.equals(role);
    }

    @JsonIgnore
    public boolean isBanned() {
        return status != null && status == STATUS_BANNED;
    }
}
