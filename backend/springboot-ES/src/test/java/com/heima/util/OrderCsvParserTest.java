package com.heima.util;

import com.heima.domain.po.Orders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderCsvParser} 单元测试（《后端开发文档》§6.4 逐行校验：{@code field|message} 错误对、容差规则）。
 */
class OrderCsvParserTest {

    /** 10 列合法数据（列下标：0 orderId … 9 payType） */
    private static List<String> validCols() {
        return new ArrayList<>(List.of(
                "ORD001", "手机", "数码", "100.00", "2", "200.00",
                "2026-09-01 10:00:00", "U001", "new", "alipay"));
    }

    private static List<String> withColumn(int index, String value) {
        List<String> cols = validCols();
        cols.set(index, value);
        return cols;
    }

    /** 单价 100.00 × 数量 3 -> 总金额用传入值，专门验证容差 */
    private static List<String> withTotalAmount(String totalAmount) {
        List<String> cols = validCols();
        cols.set(4, "3");
        cols.set(5, totalAmount);
        return cols;
    }

    // ==================== 列数 / 必填 ====================

    @Test
    @DisplayName("列数不为 10 -> field=row")
    void validate_wrongColumnCount_reportsRowLevelError() {
        String error = OrderCsvParser.validate(List.of("ORD001", "手机", "数码"));

        assertThat(error).isNotNull();
        assertThat(OrderCsvParser.fieldOf(error)).isEqualTo("row");
        assertThat(OrderCsvParser.messageOf(error)).contains("10");
    }

    @Test
    @DisplayName("必填字段为空 -> field=对应列名，message=必填字段缺失")
    void validate_blankRequiredField_reportsFieldAndMessage() {
        String error = OrderCsvParser.validate(withColumn(0, ""));

        assertThat(OrderCsvParser.fieldOf(error)).isEqualTo("orderId");
        assertThat(OrderCsvParser.messageOf(error)).isEqualTo("必填字段缺失");
        // 空格视为空
        assertThat(OrderCsvParser.fieldOf(OrderCsvParser.validate(withColumn(1, "   "))))
                .isEqualTo("productName");
    }

    // ==================== 格式非法 ====================

    @Test
    @DisplayName("单价格式非法")
    void validate_priceNotANumber() {
        String error = OrderCsvParser.validate(withColumn(3, "abc"));

        assertThat(OrderCsvParser.fieldOf(error)).isEqualTo("price");
        assertThat(OrderCsvParser.messageOf(error)).isEqualTo("单价格式非法");
    }

    @Test
    @DisplayName("数量格式非法（小数/非数字都不行）")
    void validate_quantityNotAnInteger() {
        assertThat(OrderCsvParser.fieldOf(OrderCsvParser.validate(withColumn(4, "x")))).isEqualTo("quantity");
        assertThat(OrderCsvParser.fieldOf(OrderCsvParser.validate(withColumn(4, "2.5")))).isEqualTo("quantity");
    }

    @Test
    @DisplayName("总金额格式非法")
    void validate_totalAmountNotANumber() {
        String error = OrderCsvParser.validate(withColumn(5, "N/A"));

        assertThat(OrderCsvParser.fieldOf(error)).isEqualTo("totalAmount");
        assertThat(OrderCsvParser.messageOf(error)).isEqualTo("总金额格式非法");
    }

    @Test
    @DisplayName("单价/数量必须为正数")
    void validate_priceAndQuantityMustBePositive() {
        assertThat(OrderCsvParser.fieldOf(OrderCsvParser.validate(withColumn(3, "0")))).isEqualTo("price");
        assertThat(OrderCsvParser.fieldOf(OrderCsvParser.validate(withColumn(3, "-1")))).isEqualTo("price");
        assertThat(OrderCsvParser.fieldOf(OrderCsvParser.validate(withColumn(4, "0")))).isEqualTo("quantity");
    }

    // ==================== totalAmount ≈ price × quantity ====================

    @Test
    @DisplayName("totalAmount 与 单价×数量 的容差为 0.01（含边界 0.01 通过、0.011 不通过）")
    void validate_totalAmountUsesTolerance() {
        assertThat(OrderCsvParser.TOLERANCE).isEqualByComparingTo(new BigDecimal("0.01"));

        assertThat(OrderCsvParser.validate(withTotalAmount("300.00"))).as("完全相等").isNull();
        assertThat(OrderCsvParser.validate(withTotalAmount("300.009"))).as("差值 0.009 < 0.01").isNull();
        assertThat(OrderCsvParser.validate(withTotalAmount("300.01"))).as("差值 = 容差，允许").isNull();
        assertThat(OrderCsvParser.validate(withTotalAmount("299.99"))).as("差值 = 容差，允许").isNull();

        String error = OrderCsvParser.validate(withTotalAmount("300.011"));
        assertThat(OrderCsvParser.fieldOf(error)).isEqualTo("totalAmount");
        assertThat(OrderCsvParser.messageOf(error)).isEqualTo("总金额与单价×数量不一致");
    }

    // ==================== 时间 / 枚举 ====================

    @Test
    @DisplayName("下单时间格式必须为 yyyy-MM-dd HH:mm:ss")
    void validate_orderTimeFormat() {
        String error = OrderCsvParser.validate(withColumn(6, "2026/09/01 10:00"));

        assertThat(OrderCsvParser.fieldOf(error)).isEqualTo("orderTime");
        assertThat(OrderCsvParser.messageOf(error)).isEqualTo("日期格式非法");
        assertThat(OrderCsvParser.validate(withColumn(6, "2026-09-01T10:00:00"))).isNotNull();
    }

    @Test
    @DisplayName("userType / payType 枚举校验")
    void validate_enums() {
        String userTypeError = OrderCsvParser.validate(withColumn(8, "vip"));
        assertThat(OrderCsvParser.fieldOf(userTypeError)).isEqualTo("userType");
        assertThat(OrderCsvParser.messageOf(userTypeError)).isEqualTo("用户类型枚举非法");

        String payTypeError = OrderCsvParser.validate(withColumn(9, "cash"));
        assertThat(OrderCsvParser.fieldOf(payTypeError)).isEqualTo("payType");
        assertThat(OrderCsvParser.messageOf(payTypeError)).isEqualTo("支付方式枚举非法");

        assertThat(OrderCsvParser.validate(withColumn(8, "old"))).isNull();
        assertThat(OrderCsvParser.validate(withColumn(9, "wechat"))).isNull();
    }

    // ==================== 转实体 ====================

    @Test
    @DisplayName("toEntity：10 列映射正确，promoFlag=0、region=null")
    void toEntity_mapsColumnsAndDefaults() {
        Orders order = OrderCsvParser.toEntity(validCols());

        assertThat(order.getOrderId()).isEqualTo("ORD001");
        assertThat(order.getProductName()).isEqualTo("手机");
        assertThat(order.getCategory()).isEqualTo("数码");
        assertThat(order.getPrice()).isEqualByComparingTo("100.00");
        assertThat(order.getQuantity()).isEqualTo(2);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.00");
        assertThat(order.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 0, 0));
        assertThat(order.getUserId()).isEqualTo("U001");
        assertThat(order.getUserType()).isEqualTo("new");
        assertThat(order.getPayType()).isEqualTo("alipay");
        assertThat(order.getPromoFlag()).isZero();
        assertThat(order.getRegion()).isNull();
    }

    @Test
    @DisplayName("fieldOf/messageOf 对无分隔符的错误串兜底")
    void fieldOfAndMessageOf_handleErrorWithoutSeparator() {
        assertThat(OrderCsvParser.fieldOf("boom")).isEqualTo("row");
        assertThat(OrderCsvParser.messageOf("boom")).isEqualTo("boom");
    }
}