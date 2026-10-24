package com.heima.util;

import com.heima.common.ErrorCode;
import com.heima.exception.BizException;

import java.util.Map;
import java.util.Set;

/**
 * 排序白名单（《后端开发文档》§4.3）：防止 ORDER BY 拼接用户输入导致 SQL 注入。
 */
public final class SqlSortWhitelist {

    /** 前端字段名 -> 数据库列名 */
    private static final Map<String, String> ORDER_SORT = Map.of(
            "orderTime", "order_time",
            "totalAmount", "total_amount",
            "price", "price",
            "quantity", "quantity"
    );

    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private SqlSortWhitelist() {
    }

    /**
     * 解析排序表达式，例如 {@code "totalAmount,desc"} -> {@code "total_amount DESC"}。
     * 非法输入（字段不在白名单 / 方向非法 / 格式错误）统一抛出 400。
     */
    public static Sort parseOrderSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return new Sort("order_time", "DESC");
        }
        String[] parts = sort.split(",");
        if (parts.length != 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sort 格式应为 字段,asc|desc");
        }
        String column = ORDER_SORT.get(parts[0].trim());
        String direction = parts[1].trim().toLowerCase();
        if (column == null || !DIRECTIONS.contains(direction)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的排序字段或方向：" + sort);
        }
        return new Sort(column, direction.toUpperCase());
    }

    /** 白名单校验后的排序片段（列名与方向均已确定，不含用户原始输入） */
    public record Sort(String column, String direction) {

        /** 供 XML 中 {@code ORDER BY ${sort.column} ${sort.direction}} 使用 */
        public String getColumn() {
            return column;
        }

        public String getDirection() {
            return direction;
        }
    }
}