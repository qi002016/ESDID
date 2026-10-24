package com.heima.util;

import com.heima.common.ErrorCode;
import com.heima.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.IsoFields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Granularity} 单元测试（《后端开发文档》§4.4 时间粒度分组表达式）。
 */
class GranularityTest {

    @Test
    @DisplayName("缺省粒度 = day")
    void of_blankOrDefault_isDay() {
        assertThat(Granularity.of(null)).isEqualTo(Granularity.DAY);
        assertThat(Granularity.of("")).isEqualTo(Granularity.DAY);
        assertThat(Granularity.of("   ")).isEqualTo(Granularity.DAY);
    }

    @Test
    @DisplayName("入参大小写不敏感、允许首尾空格")
    void of_isCaseInsensitiveAndTrims() {
        assertThat(Granularity.of("WEEK")).isEqualTo(Granularity.WEEK);
        assertThat(Granularity.of(" week ")).isEqualTo(Granularity.WEEK);
        assertThat(Granularity.of("Month")).isEqualTo(Granularity.MONTH);
        assertThat(Granularity.WEEK.getValue()).isEqualTo("week");
    }

    @Test
    @DisplayName("非法 granularity -> BizException(400)")
    void of_unsupportedValue_throws400() {
        assertThatThrownBy(() -> Granularity.of("hour"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getErrorCode().getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("granularity");
                });
        assertThatThrownBy(() -> Granularity.of("year")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("week 用 ISO 年(%x)+ISO 周(%v)，跨年周不串年（2026-W53 / 2027-W01）")
    void weekExpr_usesIsoYearAndWeek_soCrossYearWeeksNeverMix() {
        // SQL 常量必须是 %x-W%v：%x = ISO 年，%v = ISO 周；若写成 %Y-W%v 会把 2026-12-28（周一）错标成 2026-W01 之外的年份口径
        assertThat(Granularity.WEEK.getExpr()).contains("%x-W%v").doesNotContain("%Y");

        // ISO 周语义：2025-12-28（周日）属 2025-W52；次日 2025-12-29（周一）已属 2026-W01
        assertThat(isoWeekOf(LocalDate.of(2025, 12, 28))).isEqualTo("2025-W52");
        assertThat(isoWeekOf(LocalDate.of(2025, 12, 29))).isEqualTo("2026-W01");
        // 真正的 53 周年份（2026 年有 53 个 ISO 周）：2026-W53 跨到 2027 年，下周一才开启 2027-W01
        assertThat(isoWeekOf(LocalDate.of(2026, 12, 28))).isEqualTo("2026-W53");
        assertThat(isoWeekOf(LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
        assertThat(isoWeekOf(LocalDate.of(2027, 1, 4))).isEqualTo("2027-W01");
        // 相邻两周落在不同「周历年」：若按自然年分组就会被错误合并
        assertThat(isoWeekOf(LocalDate.of(2026, 12, 28)).substring(0, 4))
                .isNotEqualTo(isoWeekOf(LocalDate.of(2027, 1, 4)).substring(0, 4));
    }

    private static String isoWeekOf(LocalDate date) {
        return "%d-W%02d".formatted(
                date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}