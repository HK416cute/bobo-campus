package com.bobo.domain.query;

import com.bobo.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户列表查询条件（后台用户管理）。
 *
 * @author bobo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    /** 昵称 / 账号 / 学号 / 手机号 关键词 */
    private String keyword;

    /** user / admin */
    private String role;

    /** 0 正常 / 1 封禁 */
    private Integer status;

    /** 学生认证状态 */
    private Integer verifyStatus;

    private String campus;
}
