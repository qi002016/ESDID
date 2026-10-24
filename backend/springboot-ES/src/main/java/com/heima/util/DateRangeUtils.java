package com.heima.util;

import com.heima.common.ErrorCode;
import com.heima.exception.BizException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 日期区间工具（《后端开发文档》§4.2）。
 * <p>统一口径：入参 {@code yyyy-MM-dd}，缺省最近 30 天（含今天），时间条件左闭右开。</p>
 */
public final class DateRangeUtils {

    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 缺省的统计窗口天数（含今天）：最近 30 天 */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    private DateRangeUtils() {
    }

    /**
     * 缺省化与校验：缺省为最近 30 天；start &gt; end 报 400。
     *
     * @return {@code [startDate, endDate]}，均为 {@code yyyy-MM-dd}
     */
    public static String[] normalize(String start, String end) {
        return normalize(start, end, DEFAULT_WINDOW_DAYS);
    }

    /**
     * 缺省化与校验（可指定默认窗口天数，A16 使用 90 天）。
     */
    public static String[] normalize(String start, String end, int defaultDays) {
        LocalDate e = parse(end, LocalDate.now());
        LocalDate s = parse(start, e.minusDays(defaultDays - 1L));
        if (s.isAfter(e)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "startDate 不能晚于 endDate");
        }
        return new String[]{s.format(FMT), e.format(FMT)};
    }

    /** 环比：本期前一个等长区间（按自然日天数对齐） */
    public static String[] previousPeriod(String start, String end) {
        LocalDate s = LocalDate.parse(start, FMT);
        LocalDate e = LocalDate.parse(end, FMT);
        long days = ChronoUnit.DAYS.between(s, e) + 1;
        LocalDate prevEnd = s.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        return new String[]{prevStart.format(FMT), prevEnd.format(FMT)};
    }

    /** 同比：去年同期 */
    public static String[] previousYear(String start, String end) {
        return new String[]{
                LocalDate.parse(start, FMT).minusYears(1).format(FMT),
                LocalDate.parse(end, FMT).minusYears(1).format(FMT)
        };
    }

    /** 区间天数（含首尾） */
    public static long daysBetweenInclusive(String start, String end) {
        return ChronoUnit.DAYS.between(LocalDate.parse(start, FMT), LocalDate.parse(end, FMT)) + 1;
    }

    /** {@code yyyy-MM} 周期的第一天 */
    public static LocalDate monthStart(String periodValue) {
        return LocalDate.parse(periodValue + "-01", FMT);
    }

    /** {@code yyyy-MM} 周期的最后一天 */
    public static LocalDate monthEnd(String periodValue) {
        return monthStart(periodValue).withDayOfMonth(monthStart(periodValue).lengthOfMonth());
    }

    private static LocalDate parse(String value, LocalDate defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(value.trim(), FMT);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "日期格式必须为 yyyy-MM-dd：" + value);
        }
    }
}