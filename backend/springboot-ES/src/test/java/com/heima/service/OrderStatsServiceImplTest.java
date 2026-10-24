package com.heima.service;

import com.heima.domain.dto.HourlyRow;
import com.heima.domain.dto.KpiRow;
import com.heima.domain.dto.PeriodRow;
import com.heima.domain.dto.PriceIntervalRow;
import com.heima.domain.dto.StatsQueryDTO;
import com.heima.domain.dto.TrendQueryDTO;
import com.heima.domain.vo.HourlyVO;
import com.heima.domain.vo.KpiVO;
import com.heima.domain.vo.OrdersStatsVO;
import com.heima.domain.vo.PriceDistributionVO;
import com.heima.domain.vo.TrendItemVO;
import com.heima.domain.vo.TrendVO;
import com.heima.mapper.OrdersMapper;
import com.heima.service.impl.OrderStatsServiceImpl;
import com.heima.util.DateRangeUtils;
import com.heima.util.Granularity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderStatsServiceImpl} 单元测试（纯 Mockito，不依赖数据库与系统时钟）。
 * <p>覆盖《后端开发文档》§10.1 的 A3 / A5 / A7 / A8 / A10 统计口径用例。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderStatsServiceImplTest {

    @Mock
    private OrdersMapper ordersMapper;

    @InjectMocks
    private OrderStatsServiceImpl service;

    // ==================== 工具方法 ====================

    private static StatsQueryDTO statsQuery(String startDate, String endDate) {
        StatsQueryDTO query = new StatsQueryDTO();
        query.setStartDate(startDate);
        query.setEndDate(endDate);
        return query;
    }

    private static TrendQueryDTO trendQuery(String granularity, String compareType, String startDate, String endDate) {
        TrendQueryDTO query = new TrendQueryDTO();
        query.setGranularity(granularity);
        query.setCompareType(compareType);
        query.setStartDate(startDate);
        query.setEndDate(endDate);
        return query;
    }

    private static PeriodRow period(String period, String sales, long orders) {
        return new PeriodRow(period, new BigDecimal(sales), orders);
    }

    private static HourlyRow hour(int hour, int orderCount, String sales) {
        HourlyRow row = new HourlyRow();
        row.setHour(hour);
        row.setOrderCount(orderCount);
        row.setSales(new BigDecimal(sales));
        return row;
    }

    private static PriceIntervalRow interval(String range, int orderCount, String sales) {
        PriceIntervalRow row = new PriceIntervalRow();
        row.setRange(range);
        row.setOrderCount(orderCount);
        row.setSales(new BigDecimal(sales));
        return row;
    }

    private static String isoWeekOf(LocalDate date) {
        return "%d-W%02d".formatted(
                date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    // ==================== A3 KPI ====================

    @Test
    @DisplayName("A3 空库：totalSales=0、totalOrders=0、newUserRate=null、repurchaseRate=null")
    void kpi_emptyLibrary_returnsZerosAndNullRates() {
        whenKpi(new KpiRow());

        KpiVO vo = service.kpi(statsQuery("2026-09-01", "2026-09-30"));

        assertThat(vo.getTotalSales()).isEqualByComparingTo("0.00");
        assertThat(vo.getTotalOrders()).isZero();
        assertThat(vo.getAvgOrderValue()).isEqualByComparingTo("0.00");
        assertThat(vo.getHotProductCount()).isZero();
        assertThat(vo.getTotalUsers()).isZero();
        assertThat(vo.getNewUserRate()).isNull();
        assertThat(vo.getRepurchaseRate()).isNull();
    }

    @Test
    @DisplayName("A3 单用户多单：repurchaseRate = 1/1 = 1.0（4 位小数 1.0000）")
    void kpi_singleUserWithMultipleOrders_repurchaseRateIsOne() {
        whenKpi(KpiRow.builder()
                .totalSales(new BigDecimal("300.00"))
                .totalOrders(3L)
                .avgOrderValue(new BigDecimal("100.00"))
                .hotProductCount(1)
                .totalUsers(1)
                .newUserCount(1)
                .repurchaseUsers(1)
                .build());

        KpiVO vo = service.kpi(statsQuery("2026-09-01", "2026-09-30"));

        assertThat(vo.getTotalOrders()).isEqualTo(3);
        assertThat(vo.getTotalUsers()).isEqualTo(1);
        assertThat(vo.getRepurchaseRate()).isEqualByComparingTo("1.0");
        assertThat(vo.getRepurchaseRate().toPlainString()).isEqualTo("1.0000");
        assertThat(vo.getNewUserRate()).isEqualByComparingTo("1.0");
        assertThat(vo.getTotalSales()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("A3 复购率/新客率的分母都是「期内总用户数」而不是订单数")
    void kpi_ratesUseTotalUsersAsDenominator() {
        whenKpi(KpiRow.builder()
                .totalSales(new BigDecimal("1000.00"))
                .totalOrders(10L)
                .totalUsers(4)
                .newUserCount(1)
                .repurchaseUsers(2)
                .build());

        KpiVO vo = service.kpi(statsQuery("2026-09-01", "2026-09-30"));

        assertThat(vo.getNewUserRate()).isEqualByComparingTo("0.25");
        assertThat(vo.getRepurchaseRate()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("A3 日期缺省化后传给 Mapper 的区间是 30 天（入参为显式日期时原样透传）")
    void kpi_passesNormalizedRangeToMapper() {
        whenKpi(new KpiRow());

        service.kpi(statsQuery("2026-09-01", "2026-09-30"));

        verify(ordersMapper).kpi("2026-09-01", "2026-09-30", null);
    }

    private void whenKpi(KpiRow row) {
        when(ordersMapper.kpi(any(), any(), isNull())).thenReturn(row);
    }

    // ==================== A7 趋势对比 ====================

    @Test
    @DisplayName("A7 previous 跨月：2026-03-01~2026-03-31 的对比期 = 2026-01-29~2026-02-28（等长且紧邻）")
    void trend_previousAcrossMonths_usesEqualLengthWindowImmediatelyBefore() {
        when(ordersMapper.orderStats(
                        eq(Granularity.MONTH), eq("2026-03-01"), eq("2026-03-31"), isNull()))
                .thenReturn(List.of(period("2026-03", "310.00", 31L)));
        when(ordersMapper.orderStats(
                        eq(Granularity.MONTH), eq("2026-01-29"), eq("2026-02-28"), isNull()))
                .thenReturn(List.of(period("2026-02", "248.00", 24L)));

        TrendVO vo = service.trend(trendQuery("month", "previous", "2026-03-01", "2026-03-31"));

        assertThat(vo.getCompareType()).isEqualTo("previous");
        assertThat(vo.getGranularity()).isEqualTo("month");
        assertThat(vo.getCurrentPeriod().getStartDate()).isEqualTo("2026-03-01");
        assertThat(vo.getCurrentPeriod().getEndDate()).isEqualTo("2026-03-31");
        assertThat(vo.getComparePeriod().getStartDate()).isEqualTo("2026-01-29");
        assertThat(vo.getComparePeriod().getEndDate()).isEqualTo("2026-02-28");
        // 两段窗口长度必须一致
        assertThat(DateRangeUtils.daysBetweenInclusive(
                vo.getComparePeriod().getStartDate(), vo.getComparePeriod().getEndDate()))
                .isEqualTo(DateRangeUtils.daysBetweenInclusive(
                        vo.getCurrentPeriod().getStartDate(), vo.getCurrentPeriod().getEndDate()))
                .isEqualTo(31);
        // 按桶序号对齐：(310-248)/248 = 0.2500
        assertThat(vo.getItems()).hasSize(1);
        TrendItemVO item = vo.getItems().get(0);
        assertThat(item.getPeriod()).isEqualTo("2026-03");
        assertThat(item.getPrevSales()).isEqualByComparingTo("248.00");
        assertThat(item.getPrevOrders()).isEqualTo(24);
        assertThat(item.getSalesGrowthRate()).isEqualByComparingTo("0.2500");
    }

    @Test
    @DisplayName("A7 previousYear 数据不足：salesGrowthRate=null，items 仍有本期数据")
    void trend_previousYear_insufficientData_keepsCurrentItemsWithNullGrowth() {
        when(ordersMapper.orderStats(
                        eq(Granularity.DAY), eq("2026-03-01"), eq("2026-03-31"), isNull()))
                .thenReturn(List.of(
                        period("2026-03-01", "100.00", 2L),
                        period("2026-03-02", "150.00", 3L)));
        // 去年同期无任何数据
        when(ordersMapper.orderStats(
                        eq(Granularity.DAY), eq("2025-03-01"), eq("2025-03-31"), isNull()))
                .thenReturn(List.of());

        TrendVO vo = service.trend(trendQuery("day", "previousYear", "2026-03-01", "2026-03-31"));

        assertThat(vo.getCompareType()).isEqualTo("previousYear");
        assertThat(vo.getComparePeriod().getStartDate()).isEqualTo("2025-03-01");
        assertThat(vo.getComparePeriod().getEndDate()).isEqualTo("2025-03-31");
        // 本期数据必须保留（不能因为对比期缺失就整体返回空）
        assertThat(vo.getItems()).hasSize(2);
        assertThat(vo.getItems()).extracting(TrendItemVO::getPeriod)
                .containsExactly("2026-03-01", "2026-03-02");
        assertThat(vo.getItems().get(0).getCurrentSales()).isEqualByComparingTo("100.00");
        assertThat(vo.getItems().get(1).getCurrentOrders()).isEqualTo(3);
        // 对比期缺失 -> 增长率 null（不假造 0）
        assertThat(vo.getItems()).allSatisfy(item -> {
            assertThat(item.getPrevSales()).isNull();
            assertThat(item.getPrevOrders()).isNull();
            assertThat(item.getSalesGrowthRate()).isNull();
            assertThat(item.getOrdersGrowthRate()).isNull();
        });
    }

    @Test
    @DisplayName("A7 对比期 sales=0：growthRate=null（不是 Infinity/NaN，也不抛异常）")
    void trend_comparePeriodSalesZero_growthRateIsNull() {
        when(ordersMapper.orderStats(
                        eq(Granularity.MONTH), eq("2026-03-01"), eq("2026-03-31"), isNull()))
                .thenReturn(List.of(period("2026-03", "500.00", 5L)));
        when(ordersMapper.orderStats(
                        eq(Granularity.MONTH), eq("2026-01-29"), eq("2026-02-28"), isNull()))
                .thenReturn(List.of(period("2026-02", "0.00", 0L)));

        TrendVO vo = service.trend(trendQuery("month", "previous", "2026-03-01", "2026-03-31"));

        TrendItemVO item = vo.getItems().get(0);
        assertThat(item.getCurrentSales()).isEqualByComparingTo("500.00");
        assertThat(item.getPrevSales()).isEqualByComparingTo("0.00");
        assertThat(item.getSalesGrowthRate()).isNull();
        assertThat(item.getOrdersGrowthRate()).isNull();
    }

    @Test
    @DisplayName("A7 对比期桶数少于本期：按桶序号对齐，缺桶的 prev/growth 为 null")
    void trend_fewerCompareBuckets_alignsByIndexAndLeavesRestNull() {
        when(ordersMapper.orderStats(
                        eq(Granularity.DAY), eq("2026-03-01"), eq("2026-03-02"), isNull()))
                .thenReturn(List.of(
                        period("2026-03-01", "100.00", 1L),
                        period("2026-03-02", "200.00", 2L)));
        when(ordersMapper.orderStats(
                        eq(Granularity.DAY), eq("2026-02-27"), eq("2026-02-28"), isNull()))
                .thenReturn(List.of(period("2026-02-27", "50.00", 1L)));

        TrendVO vo = service.trend(trendQuery("day", "previous", "2026-03-01", "2026-03-02"));

        assertThat(vo.getItems()).hasSize(2);
        assertThat(vo.getItems().get(0).getPrevSales()).isEqualByComparingTo("50.00");
        assertThat(vo.getItems().get(0).getSalesGrowthRate()).isEqualByComparingTo("1.0000");
        assertThat(vo.getItems().get(1).getPrevSales()).isNull();
        assertThat(vo.getItems().get(1).getSalesGrowthRate()).isNull();
    }

    @Test
    @DisplayName("A7 文档口径冲突实测：month 粒度 + 31 天窗口时对比期跨两个月（2 个桶），按桶序号对齐会拿 2026-01 的部分桶对比 2026-03")
    void trend_monthGranularity_partialCompareBucketsAreAlignedByIndex() {
        when(ordersMapper.orderStats(eq(Granularity.MONTH), eq("2026-03-01"), eq("2026-03-31"), isNull()))
                .thenReturn(List.of(period("2026-03", "310.00", 31L)));
        // 真实 SQL 对 2026-01-29~2026-02-28 会返回 2 个桶：2026-01（仅含 01-29~01-31）与 2026-02
        when(ordersMapper.orderStats(eq(Granularity.MONTH), eq("2026-01-29"), eq("2026-02-28"), isNull()))
                .thenReturn(List.of(period("2026-01", "30.00", 3L), period("2026-02", "248.00", 24L)));

        TrendVO vo = service.trend(trendQuery("month", "previous", "2026-03-01", "2026-03-31"));

        // 本期只有 1 个桶，按桶序号对齐后取到的是对比期的第 1 个桶（2026-01 的部分月），
        // 2026-02 的完整桶被丢弃 —— 属文档 §A7「按桶序号对齐」与 §9.5「前等长区间」的口径冲突（见报告）
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getPrevSales()).isEqualByComparingTo("30.00");
        assertThat(vo.getItems().get(0).getSalesGrowthRate()).isEqualByComparingTo("9.3333");
        assertThat(vo.getComparePeriod().getStartDate()).isEqualTo("2026-01-29");
    }

    // ==================== A5 时间粒度聚合 ====================

    @Test
    @DisplayName("A5 granularity=week 跨年：period 形如 2026-W53 / 2027-W01，不串年")
    void stats_weekGranularity_crossYearIsoLabelsAreNotMixed() {
        StatsQueryDTO query = statsQuery("2026-12-21", "2027-01-10");
        query.setGranularity("week");
        when(ordersMapper.orderStats(
                        eq(Granularity.WEEK), eq("2026-12-21"), eq("2027-01-10"), isNull()))
                .thenReturn(List.of(
                        period("2026-W53", "120.00", 2L),
                        period("2027-W01", "340.00", 5L)));

        OrdersStatsVO vo = service.stats(query);

        assertThat(vo.getGranularity()).isEqualTo("week");
        // period 由 SQL 常量 %x-W%v（ISO 年 + ISO 周）生成，Java 侧原样返回，不做二次格式化
        assertThat(vo.getItems()).extracting(PeriodRow::getPeriod)
                .containsExactly("2026-W53", "2027-W01");
        // 跨年周用 ISO 周历年标注：2026-12-28 与 2027-01-01 同属 2026-W53，2027-01-04 才是 2027-W01
        assertThat(isoWeekOf(LocalDate.of(2026, 12, 28))).isEqualTo("2026-W53");
        assertThat(isoWeekOf(LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
        assertThat(isoWeekOf(LocalDate.of(2027, 1, 4))).isEqualTo("2027-W01");
        assertThat(vo.getItems().get(0).getSales()).isEqualByComparingTo("120.00");
        assertThat(vo.getItems().get(1).getOrders()).isEqualTo(5);
    }

    @Test
    @DisplayName("A5 区间内无数据：items 为空数组（不补零桶），granularity 原样回显")
    void stats_emptyRange_returnsEmptyItems() {
        StatsQueryDTO query = statsQuery("2026-09-01", "2026-09-30");
        query.setGranularity("day");
        when(ordersMapper.orderStats(eq(Granularity.DAY), any(), any(), isNull()))
                .thenReturn(List.of());

        OrdersStatsVO vo = service.stats(query);

        assertThat(vo.getGranularity()).isEqualTo("day");
        assertThat(vo.getItems()).isEmpty();
    }

    // ==================== A10 下单时段分布 ====================

    @Test
    @DisplayName("A10 固定返回 24 个元素（0~23），缺失小时补 0")
    void hourly_alwaysReturns24BucketsWithMissingHoursZeroed() {
        when(ordersMapper.hourly(eq("2026-09-01"), eq("2026-09-30"), isNull()))
                .thenReturn(List.of(hour(9, 5, "300.00"), hour(23, 2, "80.00")));

        HourlyVO vo = service.hourly(statsQuery("2026-09-01", "2026-09-30"));

        assertThat(vo.getHours()).hasSize(24);
        assertThat(vo.getHours()).extracting(HourlyVO.HourItemVO::getHour)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 24).boxed().toList());
        // 缺失小时补 0
        assertThat(vo.getHours().get(0).getOrderCount()).isZero();
        assertThat(vo.getHours().get(0).getSales()).isEqualByComparingTo("0.00");
        assertThat(vo.getHours().get(12).getOrderCount()).isZero();
        // 命中小时保留原值
        assertThat(vo.getHours().get(9).getOrderCount()).isEqualTo(5);
        assertThat(vo.getHours().get(9).getSales()).isEqualByComparingTo("300.00");
        assertThat(vo.getHours().get(23).getOrderCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A10 空数据也必须是 24 个全 0 元素（前端可直接映射）")
    void hourly_emptyRows_stillReturns24ZeroBuckets() {
        when(ordersMapper.hourly(any(), any(), isNull())).thenReturn(List.of());

        HourlyVO vo = service.hourly(statsQuery("2026-09-01", "2026-09-30"));

        assertThat(vo.getHours()).hasSize(24);
        assertThat(vo.getHours()).allSatisfy(item -> {
            assertThat(item.getOrderCount()).isZero();
            assertThat(item.getSales()).isEqualByComparingTo("0.00");
        });
    }

    // ==================== A8 客单价区间分布 ====================

    @Test
    @DisplayName("A8 固定 6 个区间且顺序不变，缺失区间补 0，orderRatio 之和 ≈ 1")
    void priceDistribution_sixFixedIntervalsWithRatiosSummingToOne() {
        when(ordersMapper.priceDistribution(eq("2026-09-01"), eq("2026-09-30"), isNull()))
                .thenReturn(List.of(
                        interval("0-100", 3, "150.00"),
                        interval("100-300", 7, "1400.00")));

        PriceDistributionVO vo = service.priceDistribution(statsQuery("2026-09-01", "2026-09-30"));

        assertThat(vo.getIntervals()).hasSize(6);
        assertThat(vo.getIntervals()).extracting(PriceDistributionVO.PriceIntervalVO::getRange)
                .containsExactly("0-100", "100-300", "300-500", "500-1000", "1000-5000", "5000以上");
        assertThat(vo.getTotalOrders()).isEqualTo(10);
        assertThat(vo.getTotalSales()).isEqualByComparingTo("1550.00");

        // 命中区间
        assertThat(vo.getIntervals().get(0).getOrderCount()).isEqualTo(3);
        assertThat(vo.getIntervals().get(0).getSales()).isEqualByComparingTo("150.00");
        assertThat(vo.getIntervals().get(0).getOrderRatio()).isEqualByComparingTo("0.3000");
        assertThat(vo.getIntervals().get(1).getOrderRatio()).isEqualByComparingTo("0.7000");
        // 无数据区间补 0
        assertThat(vo.getIntervals().subList(2, 6)).allSatisfy(item -> {
            assertThat(item.getOrderCount()).isZero();
            assertThat(item.getSales()).isEqualByComparingTo("0.00");
            assertThat(item.getOrderRatio()).isEqualByComparingTo("0.0000");
        });
        // orderRatio 之和 ≈ 1
        BigDecimal ratioSum = vo.getIntervals().stream()
                .map(PriceDistributionVO.PriceIntervalVO::getOrderRatio)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ratioSum.doubleValue()).isCloseTo(1.0, within(0.01));
    }

    @Test
    @DisplayName("A8 空数据：6 个区间仍返回，orderRatio 分母为 0 -> null")
    void priceDistribution_emptyData_keepsSixIntervalsWithNullRatio() {
        when(ordersMapper.priceDistribution(any(), any(), isNull())).thenReturn(List.of());

        PriceDistributionVO vo = service.priceDistribution(statsQuery("2026-09-01", "2026-09-30"));

        assertThat(vo.getIntervals()).hasSize(6);
        assertThat(vo.getTotalOrders()).isZero();
        assertThat(vo.getTotalSales()).isEqualByComparingTo("0.00");
        assertThat(vo.getIntervals()).allSatisfy(item -> {
            assertThat(item.getOrderCount()).isZero();
            assertThat(item.getOrderRatio()).isNull();
        });
    }
}