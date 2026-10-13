package com.bobo.common.exception;

import com.bobo.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理：任何异常都以统一结构返回，绝不把堆栈暴露给前端。
 *
 * @author bobo
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        return Result.fail(Result.CODE_BAD_REQUEST, firstFieldMessage(e.getBindingResult().getFieldError()));
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        return Result.fail(Result.CODE_BAD_REQUEST, firstFieldMessage(e.getBindingResult().getFieldError()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        return Result.fail(Result.CODE_BAD_REQUEST, e.getConstraintViolations().stream()
                .findFirst().map(v -> v.getMessage()).orElse("参数校验失败"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(Result.CODE_BAD_REQUEST, "缺少必要参数：" + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        // 时间格式写错是前端最常见的 400，单独给一句能照着改的提示
        String detail = e.getMostSpecificCause() == null ? "" : e.getMostSpecificCause().getMessage();
        if (detail != null && detail.contains("无法解析的时间格式")) {
            return Result.fail(Result.CODE_BAD_REQUEST, detail + "，正确写法示例：2026-01-12 15:30:00");
        }
        return Result.fail(Result.CODE_BAD_REQUEST, "请求体格式错误");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return Result.fail(Result.CODE_BAD_REQUEST, "不支持的请求方法：" + e.getMethod());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        return Result.fail("上传文件过大，请压缩后重试");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(Result.CODE_ERROR, "服务器开小差了，请稍后重试");
    }

    private String firstFieldMessage(FieldError error) {
        return error == null ? "参数校验失败" : error.getDefaultMessage();
    }
}
