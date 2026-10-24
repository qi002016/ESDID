package com.heima.exception;

import com.heima.common.ErrorCode;
import com.heima.common.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：保证任何异常都以 {@code {code,message,data}} 结构返回（《后端开发文档》§2.2）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={} message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * @Valid 校验失败（请求体/表单）
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValid(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + defaultMessage(f))
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        log.warn("参数校验失败: {}", msg);
        return Result.fail(ErrorCode.BAD_REQUEST, msg);
    }

    /**
     * @RequestParam / @PathVariable 上的 @Min @Max @Pattern 校验失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        log.warn("参数校验失败: {}", msg);
        return Result.fail(ErrorCode.BAD_REQUEST, msg);
    }

    /**
     * 日期格式非法 / 类型不匹配 / 缺少必填参数 / 请求体不可读 -> 统一 400
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public Result<Void> handleBadRequest(Exception e) {
        log.warn("参数错误: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "参数格式不正确：" + e.getMessage());
    }

    /**
     * 上传文件超过大小限制 -> 400（A18）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "上传文件超过大小限制");
    }

    /**
     * 缺少文件 part / multipart 请求不完整 -> 400（A18：文件为空）
     */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public Result<Void> handleMissingPart(Exception e) {
        log.warn("上传请求不完整: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "文件为空");
    }

    /**
     * 请求方法不支持（如前端用 GET 调用 POST 接口）-> 400，避免落到 500
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "请求方法不支持：" + e.getMethod());
    }

    /**
     * 接口路径不存在 -> 404
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public Result<Void> handleNotFound(Exception e) {
        log.warn("接口不存在: {}", e.getMessage());
        return Result.fail(ErrorCode.NOT_FOUND, "接口不存在");
    }

    /**
     * 兜底 500
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOthers(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR, "服务器内部错误，请查看服务端日志");
    }

    private String defaultMessage(FieldError fieldError) {
        return fieldError.getDefaultMessage() == null ? "参数不合法" : fieldError.getDefaultMessage();
    }
}