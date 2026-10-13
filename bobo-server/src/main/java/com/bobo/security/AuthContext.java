package com.bobo.security;

import com.bobo.common.exception.BizException;

/**
 * 当前登录用户上下文（请求级 ThreadLocal，由 {@link JwtInterceptor} 写入与清理）。
 *
 * @author bobo
 */
public final class AuthContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前用户ID；未登录抛 401 */
    public static Long userId() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw BizException.unauthorized("登录状态已失效，请重新登录");
        }
        return user.userId();
    }

    /** 当前用户ID，未登录返回 null（用于「登录可选」的接口，如商品详情） */
    public static Long userIdOrNull() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    /** 是否管理员 */
    public static boolean isAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && user.admin();
    }

    /** 强制要求管理员，否则抛 403 */
    public static void requireAdmin() {
        if (!isAdmin()) {
            throw BizException.forbidden("需要管理员权限");
        }
    }

    /** 当前登录用户快照 */
    public record LoginUser(Long userId, String username, boolean admin) {
    }
}
