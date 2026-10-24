package com.heima.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MetricsUtils} 单元测试：统一「分母为 0 返回 null」的比率/增长率口径（《后端开发文档》§4.5）。
 */
class MetricsUtilsTest {

    @Test
    @DisplayName("增长率：对比期为 0 返回 null（不是 0、Infinity 或抛 ArithmeticException）")
    void growthRate_previousZero_returnsNull() {
        assertThat(MetricsUtils.growthRate(new BigDecimal("500.00"), BigDecimal.ZERO)).isNull();
        // BigDecimal 没有 Infinity：若实现直接 divide 会抛 ArithmeticException，因此必须显式判空
        assertThat(MetricsUtils.growthRate(new BigDecimal("500.00"), new BigDecimal("0.00"))).isNull();
    }

    @Test
    @DisplayName("增长率：对比期缺失返回 null")
    void growthRate_previousNull_returnsNull() {
        assertThat(MetricsUtils.growthRate(new BigDecimal("500.00"), null)).isNull();
        assertThat(MetricsUtils.growthRate(null, new BigDecimal("500.00"))).isNull();
    }

    @Test
    @DisplayName("增长率：(310-248)/248 = 0.2500（4 位小数）")
    void growthRate_computesWithRatioScale() {
        BigDecimal rate = MetricsUtils.growthRate(new BigDecimal("310.00"), new BigDecimal("248.00"));

        assertThat(rate).isEqualByComparingTo("0.25");
        assertThat(rate.toPlainString()).isEqualTo("0.2500");
        assertThat(MetricsUtils.RATIO_SCALE).isEqualTo(4);
    }

    @Test
    @DisplayName("比率：分母为 0 返回 null，否则 4 位小数")
    void ratio_zeroDenominator_returnsNull() {
        assertThat(MetricsUtils.ratio(1L, 0L)).isNull();
        assertThat(MetricsUtils.ratio(new BigDecimal("1"), BigDecimal.ZERO)).isNull();
        assertThat(MetricsUtils.ratio(1L, 1L).toPlainString()).isEqualTo("1.0000");
        assertThat(MetricsUtils.ratio(1L, 3L).toPlainString()).isEqualTo("0.3333");
    }

    @Test
    @DisplayName("金额：null 视为 0 并保留 2 位小数")
    void scaleAmount_nullBecomesZeroWithTwoScale() {
        assertThat(MetricsUtils.scaleAmount(null)).isEqualByComparingTo("0.00");
        assertThat(MetricsUtils.scaleAmount(new BigDecimal("12.345")).toPlainString()).isEqualTo("12.35");
        assertThat(MetricsUtils.nvl(null)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(MetricsUtils.AMOUNT_SCALE).isEqualTo(2);
    }
}