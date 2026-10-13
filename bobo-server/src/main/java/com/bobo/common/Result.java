package com.bobo.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结构体。前端只需判断 {@code code == 200}。
 *
 * @param <T> 数据类型
 * @author bobo
 */
@Data
public class Result<T> implements Serializable {

    public static final int CODE_SUCCESS = 200;
    /** 参数错误 */
    public static final int CODE_BAD_REQUEST = 400;
    /** 未登录 / token 失效 */
    public static final int CODE_UNAUTHORIZED = 401;
    /** 已登录但无权限 */
    public static final int CODE_FORBIDDEN = 403;
    /** 资源不存在 */
    public static final int CODE_NOT_FOUND = 404;
    /** 业务异常（可展示给用户的原因） */
    public static final int CODE_BIZ_ERROR = 500;
    /** 系统异常 */
    public static final int CODE_ERROR = 999;

    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> ok() {
        return build(CODE_SUCCESS, "操作成功", null);
    }

    public static <T> Result<T> ok(T data) {
        return build(CODE_SUCCESS, "操作成功", data);
    }

    public static <T> Result<T> ok(String msg, T data) {
        return build(CODE_SUCCESS, msg, data);
    }

    public static <T> Result<T> fail(String msg) {
        return build(CODE_BIZ_ERROR, msg, null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return build(code, msg, null);
    }

    private static <T> Result<T> build(int code, String msg, T data) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}
