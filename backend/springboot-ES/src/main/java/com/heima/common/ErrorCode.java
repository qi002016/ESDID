package com.heima.common;

import lombok.Getter;

/**
 * 统一错误码（《后端开发文档》§2.1）。
 * 只有这 4 个码，前端按此做统一提示。
 */
@Getter
public enum ErrorCode {

    /** 参数错误：日期格式非法、缺必填参数、page&lt;1、size&gt;100、limit&gt;50、sort 不在白名单等 */
    BAD_REQUEST(400, "请求参数错误"),

    /** 资源不存在：订单号不存在、taskId 不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 状态冲突：已有 PROCESSING 导入任务 */
    CONFLICT(409, "状态冲突"),

    /** 服务器内部错误：未捕获异常兜底 */
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}