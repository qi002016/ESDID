package com.heima.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 后端统一返回结果。
 * <p>
 * 契约（《后端开发文档》§2.1 / §2.2）：{@code {code, message, data}}，
 * {@code code = 0} 表示成功，非 0 为错误码（400/404/409/500）。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 编码：0 成功，其它为错误码（见 {@link ErrorCode}） */
    private Integer code;

    /** 提示信息：成功为 success，失败为中文可读文案 */
    private String message;

    /** 业务数据 */
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.code = 0;
        result.message = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        Result<T> result = new Result<>();
        result.code = errorCode.getCode();
        result.message = message;
        return result;
    }
}