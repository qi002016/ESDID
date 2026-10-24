package com.heima.util;

import com.heima.domain.po.Orders;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 指标计算工具：统一处理「分母为 0 返回 null」的比率口径（《后端开发文档》§4.5）。
 */
public final class MetricsUtils {

    /** 比率统一保留 4 位小数（前端再格式化为百分比） */
    public static final int RATIO_SCALE = 4;

    /** 金额统一保留 2 位小数 */
    public static final int AMOUNT_SCALE = 2;

    private MetricsUtils() {
    }

    /** 比率 = part / total（0~1 小数，4 位）；分母为 0 或空返回 null */
    public static BigDecimal ratio(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.signum() == 0) {
            return null;
        }
        return part.divide(total, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 比率 = part / total（long 版本）；分母为 0 返回 null */
    public static BigDecimal ratio(long part, long total) {
        return ratio(BigDecimal.valueOf(part), BigDecimal.valueOf(total));
    }

    /** 增长率 = (current - previous) / previous；previous 为空或 0 返回 null（不返回 0/Infinity） */
    public static BigDecimal growthRate(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous).divide(previous, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 安全除法（scale 位小数）；分母为空或 0 返回 null */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale) {
        if (dividend == null || divisor == null || divisor.signum() == 0) {
            return null;
        }
        return dividend.divide(divisor, scale, RoundingMode.HALF_UP);
    }

    /** 金额保留 2 位（null 视为 0） */
    public static BigDecimal scaleAmount(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /** 空值转 0 */
    public static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 仅取两位小数的销售额字符串，供 CSV 导出使用 */
    public static String amountText(BigDecimal amount) {
        return scaleAmount(amount).toPlainString();
    }

    /** 单行 CSV 记录（列顺序与 A18 导入列定义一致，可 round-trip） */
    public static String toCsvLine(Orders order) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(CsvUtils.escape(order.getOrderId())).append(',')
                .append(CsvUtils.escape(order.getProductName())).append(',')
                .append(CsvUtils.escape(order.getCategory())).append(',')
                .append(amountText(order.getPrice())).append(',')
                .append(order.getQuantity() == null ? "0" : order.getQuantity()).append(',')
                .append(amountText(order.getTotalAmount())).append(',')
                .append(order.getOrderTime() == null ? "" : order.getOrderTime()
                        .format(CsvUtils.TIME_FMT)).append(',')
                .append(CsvUtils.escape(order.getUserId())).append(',')
                .append(CsvUtils.escape(order.getUserType())).append(',')
                .append(CsvUtils.escape(order.getPayType()));
        return sb.toString();
    }
}