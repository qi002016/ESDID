package com.heima.util;

import com.heima.common.ErrorCode;
import com.heima.exception.BizException;

/**
 * 时间粒度枚举（《后端开发文档》§4.4）。
 * <p>SQL 片段为固定常量，绝不拼接用户输入。</p>
 */
public enum Granularity {

    /** 日：2026-09-01 */
    DAY("day", "DATE_FORMAT(order_time, '%Y-%m-%d')"),

    /** ISO 周：2026-W38（%x = ISO 年，%v = ISO 周，跨年不串年） */
    WEEK("week", "DATE_FORMAT(order_time, '%x-W%v')"),

    /** 月：2026-09 */
    MONTH("month", "DATE_FORMAT(order_time, '%Y-%m')");

    /** 接口入参值 */
    private final String value;

    /** 分组表达式（常量） */
    private final String expr;

    Granularity(String value, String expr) {
        this.value = value;
        this.expr = expr;
    }

    public String getValue() {
        return value;
    }

    public String getExpr() {
        return expr;
    }

    /**
     * 解析入参，缺省 day；非法值抛 400。
     */
    public static Granularity of(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "granularity 仅支持 day/week/month");
        }
    }
}