package com.heima.util;

import com.heima.domain.po.Orders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CsvUtils} 单元测试（《后端开发文档》§6.4 导入解析、§7 导出转义）。
 */
class CsvUtilsTest {

    /** 一行合法的 10 列 CSV（与数据字典列顺序一致） */
    private static final String VALID_LINE =
            "ORD001,手机,数码,100.00,2,200.00,2026-09-01 10:00:00,U001,new,alipay";

    @Test
    @DisplayName("普通行解析为 10 列，字段两侧空格被裁剪")
    void split_plainLine_yieldsTenFields() {
        List<String> cols = CsvUtils.split(" ORD001 , 手机 ,数码,100.00,2,200.00,2026-09-01 10:00:00,U001,new,alipay ");

        assertThat(cols).hasSize(CsvUtils.FIELDS.length).hasSize(10);
        assertThat(cols.get(0)).isEqualTo("ORD001");
        assertThat(cols.get(1)).isEqualTo("手机");
        assertThat(cols.get(9)).isEqualTo("alipay");
    }

    @Test
    @DisplayName("BOM 前缀（A12 导出的文件）可被直接重新解析：首列不带 \\uFEFF")
    void split_bomPrefixedLine_stripsBom() {
        List<String> cols = CsvUtils.split("\uFEFF" + VALID_LINE);

        assertThat(cols).hasSize(10);
        assertThat(cols.get(0)).isEqualTo("ORD001");
        assertThat(cols.get(0)).doesNotContain("\uFEFF");
        // toEntity 会再兜底清一次 BOM
        assertThat(OrderCsvParser.toEntity(cols).getOrderId()).isEqualTo("ORD001");
    }

    @Test
    @DisplayName("表头识别：兼容中英文表头，且兼容 BOM 前缀")
    void isHeader_acceptsChineseAndEnglishHeader_withOrWithoutBom() {
        assertThat(CsvUtils.isHeader(CsvUtils.HEADER)).isTrue();
        assertThat(CsvUtils.isHeader("\uFEFF" + CsvUtils.HEADER)).isTrue();
        assertThat(CsvUtils.isHeader("orderid,productname,category,price,quantity,totalamount,"
                + "ordertime,userid,usertype,paytype")).isTrue();
        assertThat(CsvUtils.isHeader(VALID_LINE)).isFalse();
        assertThat(CsvUtils.isHeader(null)).isFalse();
    }

    @Test
    @DisplayName("引号包裹的字段可包含逗号")
    void split_keepsCommasInsideQuotes() {
        List<String> cols = CsvUtils.split(
                "ORD002,\"手机, Pro\",数码,100.00,2,200.00,2026-09-01 10:00:00,U002,new,alipay");

        assertThat(cols).hasSize(10);
        assertThat(cols.get(1)).isEqualTo("手机, Pro");
    }

    @Test
    @DisplayName("连续两个引号表示一个引号字符")
    void split_unescapesDoubledQuotes() {
        assertThat(CsvUtils.split("\"a\"\"b\",c")).containsExactly("a\"b", "c");
        assertThat(CsvUtils.split("\"\"\"x\"\"\",y")).containsExactly("\"x\"", "y");
    }

    @Test
    @DisplayName("导出转义：仅含逗号/引号/换行时用双引号包裹")
    void escape_wrapsOnlyWhenNeeded() {
        assertThat(CsvUtils.escape("plain")).isEqualTo("plain");
        assertThat(CsvUtils.escape("手机,Pro")).isEqualTo("\"手机,Pro\"");
        assertThat(CsvUtils.escape("he said \"hi\"")).isEqualTo("\"he said \"\"hi\"\"\"");
        assertThat(CsvUtils.escape("line1\nline2")).startsWith("\"").endsWith("\"");
        assertThat(CsvUtils.escape(null)).isEmpty();
        assertThat(CsvUtils.escape("\uFEFFabc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("A12 导出行可被 A18 直接重新导入：split 后列数=10 且逐行校验通过")
    void exportedLine_canBeReimported() {
        Orders order = new Orders();
        order.setOrderId("ORD003");
        order.setProductName("手机,Pro");           // 触发引号转义
        order.setCategory("数码");
        order.setPrice(new BigDecimal("100.00"));
        order.setQuantity(2);
        order.setTotalAmount(new BigDecimal("200.00"));
        order.setOrderTime(LocalDateTime.of(2026, 9, 1, 10, 0, 0));
        order.setUserId("U003");
        order.setUserType("new");
        order.setPayType("alipay");

        String line = MetricsUtils.toCsvLine(order);
        assertThat(line).contains("\"手机,Pro\"");

        // 导出文件首行带 BOM：表头行被识别为表头，数据行可正常解析
        assertThat(CsvUtils.isHeader("\uFEFF" + CsvUtils.HEADER)).isTrue();
        List<String> cols = CsvUtils.split(line);
        assertThat(cols).hasSize(10);
        assertThat(cols.get(1)).isEqualTo("手机,Pro");
        assertThat(OrderCsvParser.validate(cols)).isNull();

        Orders parsed = OrderCsvParser.toEntity(cols);
        assertThat(parsed.getOrderId()).isEqualTo("ORD003");
        assertThat(parsed.getProductName()).isEqualTo("手机,Pro");
        assertThat(parsed.getTotalAmount()).isEqualByComparingTo("200.00");
        assertThat(parsed.getOrderTime()).isEqualTo(order.getOrderTime());
    }
}