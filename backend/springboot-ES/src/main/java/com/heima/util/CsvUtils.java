package com.heima.util;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 工具（《后端开发文档》§6.4 校验、§7 导出）。
 * <p>列顺序固定为：订单号,商品名,品类,单价,数量,总金额,下单时间,用户ID,用户类型,支付方式。</p>
 */
public final class CsvUtils {

    /** CSV 中的时间格式 */
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 表头（导出与导入共用同一列顺序） */
    public static final String HEADER = "订单号,商品名,品类,单价,数量,总金额,下单时间,用户ID,用户类型,支付方式";

    /** 字段名（与校验错误明细中的 field 对应） */
    public static final String[] FIELDS = {
            "orderId", "productName", "category", "price", "quantity",
            "totalAmount", "orderTime", "userId", "userType", "payType"
    };

    private CsvUtils() {
    }

    /**
     * 解析一行 CSV（支持双引号包裹、引号转义、字段内逗号）。
     * <p>不支持字段内换行（导出时虽会转义，但导入按行读取，与文档实现一致）。</p>
     */
    public static List<String> split(String line) {
        List<String> fields = new ArrayList<>(16);
        if (line == null) {
            return fields;
        }
        // 去掉可能存在的 UTF-8 BOM（A12 导出的文件带 BOM，可被 A18 直接重新导入）
        line = line.replace("\uFEFF", "");
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // 连续两个引号表示一个引号字符
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    /** 判断首行是否为表头（兼容有/无表头的 CSV） */
    public static boolean isHeader(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.replace("\uFEFF", "").trim();
        return trimmed.startsWith("订单号") || trimmed.toLowerCase().startsWith("orderid");
    }

    /** 导出字段转义：含逗号/引号/换行时用双引号包裹 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\uFEFF", "");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}