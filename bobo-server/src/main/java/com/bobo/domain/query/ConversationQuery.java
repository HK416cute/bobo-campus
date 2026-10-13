package com.bobo.domain.query;

import com.bobo.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话列表查询条件。
 *
 * @author bobo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationQuery extends PageQuery {

    /** 当前登录用户的角色视角：buyer / seller / all */
    private String role = "all";
}
