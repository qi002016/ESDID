package com.heima.util;

import com.heima.common.ErrorCode;
import com.heima.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DateRangeUtils} 单元测试（《后端开发文档》§4.2 日期缺省与校验、§9.5 环比口径）。
 * <p>全部使用固定日期，不依赖运行时钟（仅「缺省到今天」一条按今天的值做区间断言，且用 isIn 规避跨零点）。</p>
 */
class DateRangeUtilsTest {

    // ==================== 缺省窗口 ====================

    @Test
    @DisplayName("endDate 缺省：startDate 缺省时取 endDate 前等长的 30 天窗口（含首尾）")
    void normalize_defaultWindow_isInclusive30Days() {
        String[] range = DateRangeUtils.normalize(null, "2026-03-31");

        assertThat(range).containsExactly("2026-03-02", "2026-03-31");
        assertThat(DateRangeUtils.daysBetweenInclusive(range[0], range[1])).isEqualTo(30);
        assertThat(DateRangeUtils.DEFAULT_WINDOW_DAYS).isEqualTo(30);
    }

    @Test
    @DisplayName("startDate/endDate 全缺省：最近 30 天（含今天），不依赖具体时钟值")
    void normalize_bothBlank_defaultsToLast30DaysEndingToday() {
        LocalDate before = LocalDate.now();
        String[] range = DateRangeUtils.normalize(null, null);

        // 允许跨零点：endDate 只能是调用前或调用后的当天
        assertThat(range[1]).isIn(before.format(DateRangeUtils.FMT), before.plusDays(1).format(DateRangeUtils.FMT));
        assertThat(DateRangeUtils.daysBetweenInclusive(range[0], range[1])).isEqualTo(30);
        // 空白串与 null 等价
        assertThat(DateRangeUtils.normalize("  ", " ")).hasSize(2);
    }

    @Test
    @DisplayName("可指定缺省窗口天数（A16 RFM 使用 90 天）")
    void normalize_customDefaultWindow_supportsA16NinetyDays() {
        assertThat(DateRangeUtils.normalize(null, "2026-03-31", 90))
                .containsExactly("2026-01-01", "2026-03-31");
    }

    // ==================== 非法入参 -> 400 ====================

    @Test
    @DisplayName("startDate > endDate -> BizException(400)")
    void normalize_startAfterEnd_throws400() {
        assertThatThrownBy(() -> DateRangeUtils.normalize("2026-04-01", "2026-03-31"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getErrorCode().getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("startDate");
                });
    }

    @Test
    @DisplayName("日期格式非法 -> BizException(400)")
    void normalize_invalidDateFormat_throws400() {
        assertThatThrownBy(() -> DateRangeUtils.normalize("2026/03/01", "2026-03-31"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("yyyy-MM-dd");
                });
    }

    // ==================== 环比 / 同比 ====================

    @Test
    @DisplayName("环比跨月：2026-03-01~2026-03-31 -> 2026-01-29~2026-02-28（文档 §10.1 用例）")
    void previousPeriod_crossMonth_matchesSpecExample() {
        String[] prev = DateRangeUtils.previousPeriod("2026-03-01", "2026-03-31");

        assertThat(prev).containsExactly("2026-01-29", "2026-02-28");
    }

    @Test
    @DisplayName("环比恒为「紧邻本期之前的等长区间」：单日、闰年 2 月边界都对")
    void previousPeriod_isAlwaysEqualLengthAndAdjacent() {
        // 单日
        assertThat(DateRangeUtils.previousPeriod("2026-03-01", "2026-03-01"))
                .containsExactly("2026-02-28", "2026-02-28");
        // 闰年：31 天窗口的前一段必然跨 2 月 29 日
        assertThat(DateRangeUtils.previousPeriod("2024-03-01", "2024-03-31"))
                .containsExactly("2024-01-30", "2024-02-29");

        String[][] windows = {
                {"2026-01-01", "2026-02-15"},
                {"2025-12-15", "2026-01-14"},
                {"2026-03-01", "2026-03-31"}
        };
        for (String[] window : windows) {
            String[] prev = DateRangeUtils.previousPeriod(window[0], window[1]);
            assertThat(DateRangeUtils.daysBetweenInclusive(prev[0], prev[1]))
                    .as("窗口长度必须相等：%s", String.join("~", window))
                    .isEqualTo(DateRangeUtils.daysBetweenInclusive(window[0], window[1]));
            assertThat(LocalDate.parse(prev[1], DateRangeUtils.FMT).plusDays(1))
                    .as("对比期必须紧邻本期之前：%s", String.join("~", window))
                    .isEqualTo(LocalDate.parse(window[0], DateRangeUtils.FMT));
        }
    }

    @Test
    @DisplayName("同比：去年同期（闰日回退到 2 月 28 日）")
    void previousYear_returnsSameWindowLastYear() {
        assertThat(DateRangeUtils.previousYear("2026-03-01", "2026-03-31"))
                .containsExactly("2025-03-01", "2025-03-31");
        assertThat(DateRangeUtils.previousYear("2024-02-29", "2024-02-29"))
                .containsExactly("2023-02-28", "2023-02-28");
    }

    // ==================== 月周期 ====================

    @Test
    @DisplayName("monthStart/monthEnd：含闰年 2 月与 12 月末")
    void monthStartAndMonthEnd_coverLeapFebruary() {
        assertThat(DateRangeUtils.monthStart("2026-02")).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(DateRangeUtils.monthEnd("2026-02")).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(DateRangeUtils.monthEnd("2024-02")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(DateRangeUtils.monthEnd("2026-12")).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("daysBetweenInclusive 含首尾")
    void daysBetweenInclusive_countsBothEnds() {
        assertThat(DateRangeUtils.daysBetweenInclusive("2026-03-01", "2026-03-31")).isEqualTo(31);
        assertThat(DateRangeUtils.daysBetweenInclusive("2026-03-01", "2026-03-01")).isEqualTo(1);
    }
}