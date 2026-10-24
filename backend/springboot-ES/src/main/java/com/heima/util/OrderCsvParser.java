package com.heima.util;

import com.heima.domain.po.Orders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * A18 CSV 行校验与转换（《后端开发文档》§6.4）。
 * <p>返回 {@code null} 表示校验通过；否则返回 {@code 字段|错误原因}，便于写入错误明细。</p>
 */
public final class OrderCsvParser {

    /** 单价 × 数量 与总金额允许的误差 */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private static final Set<String> PAY_TYPES = Set.of("alipay", "wechat", "card");
    private static final Set<String> USER_TYPES = Set.of("new", "old");

    private OrderCsvParser() {
    }

    /**
     * 逐行校验。
     *
     * @return {@code null} 校验通过；否则 {@code field|message}
     */
    public static String validate(List<String> cols) {
        if (cols.size() != CsvUtils.FIELDS.length) {
            return "row|列数必须为 10 列，当前 " + cols.size();
        }
        for (int i = 0; i < CsvUtils.FIELDS.length; i++) {
            if (isBlank(cols.get(i))) {
                return CsvUtils.FIELDS[i] + "|必填字段缺失";
            }
        }

        BigDecimal price;
        BigDecimal total;
        int quantity;
        try {
            price = new BigDecimal(cols.get(3));
        } catch (NumberFormatException e) {
            return "price|单价格式非法";
        }
        try {
            quantity = Integer.parseInt(cols.get(4));
        } catch (NumberFormatException e) {
            return "quantity|数量格式非法";
        }
        try {
            total = new BigDecimal(cols.get(5));
        } catch (NumberFormatException e) {
            return "totalAmount|总金额格式非法";
        }
        if (price.signum() <= 0) {
            return "price|单价必须为正数";
        }
        if (quantity <= 0) {
            return "quantity|数量必须为正数";
        }
        if (price.multiply(BigDecimal.valueOf(quantity)).subtract(total).abs().compareTo(TOLERANCE) > 0) {
            return "totalAmount|总金额与单价×数量不一致";
        }
        try {
            LocalDateTime.parse(cols.get(6), CsvUtils.TIME_FMT);
        } catch (DateTimeParseException e) {
            return "orderTime|日期格式非法";
        }
        if (!USER_TYPES.contains(cols.get(8))) {
            return "userType|用户类型枚举非法";
        }
        if (!PAY_TYPES.contains(cols.get(9))) {
            return "payType|支付方式枚举非法";
        }
        return null;
    }

    /**
     * 转为实体。CSV 只有 10 列：{@code promoFlag} 默认 0、{@code region} 为 null。
     */
    public static Orders toEntity(List<String> cols) {
        Orders order = new Orders();
        order.setOrderId(clean(cols.get(0)));
        order.setProductName(clean(cols.get(1)));
        order.setCategory(clean(cols.get(2)));
        order.setPrice(new BigDecimal(cols.get(3)));
        order.setQuantity(Integer.valueOf(cols.get(4)));
        order.setTotalAmount(new BigDecimal(cols.get(5)));
        order.setOrderTime(LocalDateTime.parse(cols.get(6), CsvUtils.TIME_FMT));
        order.setUserId(clean(cols.get(7)));
        order.setUserType(clean(cols.get(8)));
        order.setPayType(clean(cols.get(9)));
        order.setPromoFlag(0);
        order.setRegion(null);
        return order;
    }

    /** 从 {@code field|message} 中取字段名 */
    public static String fieldOf(String error) {
        int idx = error.indexOf('|');
        return idx < 0 ? "row" : error.substring(0, idx);
    }

    /** 从 {@code field|message} 中取错误原因 */
    public static String messageOf(String error) {
        int idx = error.indexOf('|');
        return idx < 0 ? error : error.substring(idx + 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String clean(String value) {
        return value == null ? null : value.replace("\uFEFF", "").trim();
    }
}