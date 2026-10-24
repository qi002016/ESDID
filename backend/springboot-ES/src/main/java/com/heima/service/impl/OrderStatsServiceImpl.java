package com.heima.service.impl;

import com.heima.common.ErrorCode;
import com.heima.domain.dto.CampaignMetricsRow;
import com.heima.domain.dto.CampaignQueryDTO;
import com.heima.domain.dto.ContributionRow;
import com.heima.domain.dto.HourlyRow;
import com.heima.domain.dto.KpiRow;
import com.heima.domain.dto.PeriodRow;
import com.heima.domain.dto.PriceIntervalRow;
import com.heima.domain.dto.RankingQueryDTO;
import com.heima.domain.dto.RegionRow;
import com.heima.domain.dto.StatsQueryDTO;
import com.heima.domain.dto.TrendQueryDTO;
import com.heima.domain.dto.UserTypeAggRow;
import com.heima.domain.vo.CampaignEffectVO;
import com.heima.domain.vo.CategoryContributionVO;
import com.heima.domain.vo.HourlyVO;
import com.heima.domain.vo.KpiVO;
import com.heima.domain.vo.OrdersStatsVO;
import com.heima.domain.vo.PriceDistributionVO;
import com.heima.domain.vo.RankingVO;
import com.heima.domain.vo.RegionSalesVO;
import com.heima.domain.vo.TrendItemVO;
import com.heima.domain.vo.TrendVO;
import com.heima.domain.vo.UserAnalysisVO;
import com.heima.exception.BizException;
import com.heima.mapper.OrdersMapper;
import com.heima.service.IOrderStatsService;
import com.heima.util.DateRangeUtils;
import com.heima.util.Granularity;
import com.heima.util.MetricsUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单统计服务实现（A3/A4/A5/A7/A8/A9/A10/A11/A15/A17）。
 * <p>统一口径见《后端开发文档》§4.5：数组空返回 {@code []}、数值 0、比率无法计算返回 {@code null}。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderStatsServiceImpl implements IOrderStatsService {

    /** A8 固定 6 个客单价区间，顺序不可变 */
    private static final List<String> PRICE_RANGES =
            List.of("0-100", "100-300", "300-500", "500-1000", "1000-5000", "5000以上");

    /** A10 固定 24 个时段 */
    private static final int HOURS_OF_DAY = 24;

    private final OrdersMapper ordersMapper;

    // ==================== A3 KPI ====================

    @Override
    public KpiVO kpi(StatsQueryDTO query) {
        String[] range = normalize(query);
        KpiRow row = ordersMapper.kpi(range[0], range[1], query.getCategory());

        long totalUsers = intValue(row.getTotalUsers());
        long newUserCount = intValue(row.getNewUserCount());
        long repurchaseUsers = intValue(row.getRepurchaseUsers());

        return KpiVO.builder()
                .totalSales(MetricsUtils.scaleAmount(row.getTotalSales()))
                .totalOrders((int) intValue(row.getTotalOrders()))
                .avgOrderValue(MetricsUtils.scaleAmount(row.getAvgOrderValue()))
                .hotProductCount((int) intValue(row.getHotProductCount()))
                .totalUsers((int) totalUsers)
                // 复购率/新客率分母均为期内总用户数，0/0 返回 null
                .newUserRate(MetricsUtils.ratio(newUserCount, totalUsers))
                .repurchaseRate(MetricsUtils.ratio(repurchaseUsers, totalUsers))
                .build();
    }

    // ==================== A4 商品排行 ====================

    @Override
    public List<RankingVO> ranking(RankingQueryDTO query) {
        String[] range = normalize(query.getStartDate(), query.getEndDate());
        // rankBy 映射为两个固定列名之一，绝不拼接用户输入
        String orderBy = "quantity".equalsIgnoreCase(query.getRankBy()) ? "quantity" : "sales";
        int limit = query.getLimit() == null ? 10 : query.getLimit();
        if (limit > 50) {
            limit = 50;
        }
        query.setStartDate(range[0]);
        query.setEndDate(range[1]);

        List<RankingVO> list = ordersMapper.productRanking(query, orderBy, limit);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setRank(i + 1);
        }
        return list;
    }

    // ==================== A5 时间粒度聚合 ====================

    @Override
    public OrdersStatsVO stats(StatsQueryDTO query) {
        String[] range = normalize(query);
        Granularity granularity = Granularity.of(query.getGranularity());
        List<PeriodRow> items = ordersMapper.orderStats(granularity, range[0], range[1], query.getCategory());
        return OrdersStatsVO.builder()
                .granularity(granularity.getValue())
                .items(items)
                .build();
    }

    // ==================== A7 趋势对比 ====================

    @Override
    public TrendVO trend(TrendQueryDTO query) {
        String[] current = DateRangeUtils.normalize(query.getStartDate(), query.getEndDate());
        Granularity granularity = Granularity.of(query.getGranularity());
        boolean previousYear = "previousYear".equalsIgnoreCase(query.getCompareType());
        String[] compare = previousYear
                ? DateRangeUtils.previousYear(current[0], current[1])
                : DateRangeUtils.previousPeriod(current[0], current[1]);

        List<PeriodRow> currentRows = ordersMapper.orderStats(granularity, current[0], current[1], query.getCategory());
        List<PeriodRow> compareRows = ordersMapper.orderStats(granularity, compare[0], compare[1], query.getCategory());

        // 两段长度相同、粒度相同：第 i 个桶互相对应（本接口最易写错的地方）
        List<TrendItemVO> items = new ArrayList<>(currentRows.size());
        for (int i = 0; i < currentRows.size(); i++) {
            PeriodRow cur = currentRows.get(i);
            PeriodRow prev = i < compareRows.size() ? compareRows.get(i) : null;

            BigDecimal currentSales = MetricsUtils.scaleAmount(cur.getSales());
            BigDecimal prevSales = prev == null ? null : MetricsUtils.scaleAmount(prev.getSales());
            Integer currentOrders = (int) intValue(cur.getOrders());
            Integer prevOrders = prev == null ? null : (int) intValue(prev.getOrders());

            items.add(TrendItemVO.builder()
                    .period(cur.getPeriod())
                    .currentSales(currentSales)
                    .currentOrders(currentOrders)
                    .prevSales(prevSales)
                    .prevOrders(prevOrders)
                    // 对比期缺失或为 0 时返回 null，绝不返回 0/Infinity
                    .salesGrowthRate(MetricsUtils.growthRate(currentSales, prevSales))
                    .ordersGrowthRate(MetricsUtils.growthRate(
                            BigDecimal.valueOf(currentOrders),
                            prevOrders == null ? null : BigDecimal.valueOf(prevOrders)))
                    .build());
        }

        return TrendVO.builder()
                .granularity(granularity.getValue())
                .compareType(previousYear ? "previousYear" : "previous")
                .currentPeriod(new TrendVO.PeriodRange(current[0], current[1]))
                .comparePeriod(new TrendVO.PeriodRange(compare[0], compare[1]))
                .items(items)
                .build();
    }

    // ==================== A8 客单价区间分布 ====================

    @Override
    public PriceDistributionVO priceDistribution(StatsQueryDTO query) {
        String[] range = normalize(query);
        List<PriceIntervalRow> rows = ordersMapper.priceDistribution(range[0], range[1], query.getCategory());

        Map<String, PriceIntervalRow> rowMap = new HashMap<>(rows.size());
        for (PriceIntervalRow row : rows) {
            rowMap.put(row.getRange(), row);
        }

        // 两遍：先按固定 6 个区间累计总量，再按总量算占比，保证 totalOrders/totalSales 与 intervals 完全自洽
        int totalOrders = 0;
        BigDecimal totalSales = BigDecimal.ZERO;
        for (String label : PRICE_RANGES) {
            PriceIntervalRow row = rowMap.get(label);
            totalOrders += row == null ? 0 : (int) intValue(row.getOrderCount());
            totalSales = totalSales.add(row == null ? BigDecimal.ZERO : MetricsUtils.nvl(row.getSales()));
        }

        // 补齐固定 6 个区间，保证直方图形状稳定
        List<PriceDistributionVO.PriceIntervalVO> intervals = new ArrayList<>(PRICE_RANGES.size());
        for (String label : PRICE_RANGES) {
            PriceIntervalRow row = rowMap.get(label);
            int orderCount = row == null ? 0 : (int) intValue(row.getOrderCount());
            BigDecimal sales = row == null ? BigDecimal.ZERO : MetricsUtils.scaleAmount(row.getSales());
            intervals.add(PriceDistributionVO.PriceIntervalVO.builder()
                    .range(label)
                    .orderCount(orderCount)
                    .sales(MetricsUtils.scaleAmount(sales))
                    .orderRatio(MetricsUtils.ratio(orderCount, totalOrders))
                    .build());
        }

        return PriceDistributionVO.builder()
                .totalOrders(totalOrders)
                .totalSales(MetricsUtils.scaleAmount(totalSales))
                .intervals(intervals)
                .build();
    }

    // ==================== A9 品类贡献（帕累托） ====================

    @Override
    public CategoryContributionVO categoryContribution(StatsQueryDTO query) {
        // 文档未定义 category，前端也确实不传：仅按日期过滤
        String[] range = normalize(query);
        List<ContributionRow> rows = ordersMapper.categoryContribution(range[0], range[1]);

        List<CategoryContributionVO.ContributionItemVO> items = new ArrayList<>(rows.size());
        BigDecimal totalSales = BigDecimal.ZERO;
        int rank = 1;
        for (ContributionRow row : rows) {
            BigDecimal sales = MetricsUtils.scaleAmount(row.getSales());
            totalSales = totalSales.add(sales);
            items.add(CategoryContributionVO.ContributionItemVO.builder()
                    .rank(rank++)
                    .category(row.getCategory())
                    .sales(sales)
                    .salesRatio(row.getSalesRatio())
                    .cumulativeRatio(row.getCumulativeRatio())
                    .build());
        }
        return CategoryContributionVO.builder()
                .totalSales(MetricsUtils.scaleAmount(totalSales))
                .items(items)
                .build();
    }

    // ==================== A10 下单时段分布 ====================

    @Override
    public HourlyVO hourly(StatsQueryDTO query) {
        String[] range = normalize(query);
        List<HourlyRow> rows = ordersMapper.hourly(range[0], range[1], query.getCategory());

        Map<Integer, HourlyRow> rowMap = new HashMap<>(rows.size());
        for (HourlyRow row : rows) {
            rowMap.put(row.getHour(), row);
        }

        // 必须补齐 0~23 共 24 个元素，便于前端直接映射
        List<HourlyVO.HourItemVO> hours = new ArrayList<>(HOURS_OF_DAY);
        for (int hour = 0; hour < HOURS_OF_DAY; hour++) {
            HourlyRow row = rowMap.get(hour);
            hours.add(HourlyVO.HourItemVO.builder()
                    .hour(hour)
                    .orderCount(row == null ? 0 : (int) intValue(row.getOrderCount()))
                    .sales(row == null ? BigDecimal.ZERO : MetricsUtils.scaleAmount(row.getSales()))
                    .build());
        }
        return HourlyVO.builder().hours(hours).build();
    }

    // ==================== A11 用户分析 ====================

    @Override
    public UserAnalysisVO userAnalysis(StatsQueryDTO query) {
        String[] range = normalize(query);
        String category = query.getCategory();

        List<UserTypeAggRow> userTypeRows = ordersMapper.userTypeAgg(range[0], range[1], category);
        long newUserCount = 0, oldUserCount = 0, newUserOrderCount = 0, oldUserOrderCount = 0;
        BigDecimal newUserSales = BigDecimal.ZERO, oldUserSales = BigDecimal.ZERO;
        for (UserTypeAggRow row : userTypeRows) {
            if ("new".equalsIgnoreCase(row.getUserType())) {
                newUserCount = intValue(row.getUserCount());
                newUserOrderCount = intValue(row.getOrderCount());
                newUserSales = MetricsUtils.nvl(row.getSales());
            } else if ("old".equalsIgnoreCase(row.getUserType())) {
                oldUserCount = intValue(row.getUserCount());
                oldUserOrderCount = intValue(row.getOrderCount());
                oldUserSales = MetricsUtils.nvl(row.getSales());
            }
        }

        long totalUsers = intValue(ordersMapper.distinctUserCount(range[0], range[1], category));
        long repurchaseUsers = intValue(ordersMapper.repurchaseUserCount(range[0], range[1], category));

        return UserAnalysisVO.builder()
                .totalUsers((int) totalUsers)
                .newUserCount((int) newUserCount)
                .oldUserCount((int) oldUserCount)
                .newUserSales(MetricsUtils.scaleAmount(newUserSales))
                .oldUserSales(MetricsUtils.scaleAmount(oldUserSales))
                // 客单价用订单数而不是用户数作分母
                .newUserAvgOrderValue(MetricsUtils.divide(newUserSales, BigDecimal.valueOf(newUserOrderCount),
                        MetricsUtils.AMOUNT_SCALE))
                .oldUserAvgOrderValue(MetricsUtils.divide(oldUserSales, BigDecimal.valueOf(oldUserOrderCount),
                        MetricsUtils.AMOUNT_SCALE))
                .newUserRate(MetricsUtils.ratio(newUserCount, totalUsers))
                .repurchaseRate(MetricsUtils.ratio(repurchaseUsers, totalUsers))
                .payTypeRates(ordersMapper.payTypeRates(range[0], range[1], category))
                .build();
    }

    // ==================== A15 促销活动效果 ====================

    @Override
    public CampaignEffectVO campaignEffect(CampaignQueryDTO query) {
        String start = query.getStartDate();
        String end = query.getEndDate();
        if (start.compareTo(end) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "startDate 不能晚于 endDate");
        }
        // 非活动期 = 活动期前等长区间（不是同期 promoFlag = 0）
        String[] nonActivity = DateRangeUtils.previousPeriod(start, end);

        CampaignMetricsRow activity = ordersMapper.campaignMetrics(start, end, query.getCategory());
        CampaignMetricsRow non = ordersMapper.campaignMetrics(nonActivity[0], nonActivity[1], query.getCategory());

        CampaignEffectVO.CampaignMetricsVO activityVO = toMetricsVO(activity);
        CampaignEffectVO.CampaignMetricsVO nonActivityVO = toMetricsVO(non);

        BigDecimal promotionPullRate = null;
        if (intValue(activity.getPromoOrderCount()) > 0) {
            promotionPullRate = MetricsUtils.ratio(MetricsUtils.nvl(activity.getPromoSales()),
                    MetricsUtils.nvl(activity.getSales()));
        }

        return CampaignEffectVO.builder()
                .activityPeriodStart(start)
                .activityPeriodEnd(end)
                .nonActivityPeriodStart(nonActivity[0])
                .nonActivityPeriodEnd(nonActivity[1])
                .activity(activityVO)
                .nonActivity(nonActivityVO)
                .salesGrowthRate(MetricsUtils.growthRate(activityVO.getSales(), nonActivityVO.getSales()))
                .ordersGrowthRate(MetricsUtils.growthRate(
                        BigDecimal.valueOf(intValue(activity.getOrders())),
                        BigDecimal.valueOf(intValue(non.getOrders()))))
                .promotionPullRate(promotionPullRate)
                .build();
    }

    private CampaignEffectVO.CampaignMetricsVO toMetricsVO(CampaignMetricsRow row) {
        long orders = intValue(row.getOrders());
        BigDecimal sales = MetricsUtils.scaleAmount(row.getSales());
        long promoOrders = intValue(row.getPromoOrderCount());
        return CampaignEffectVO.CampaignMetricsVO.builder()
                .orders(orders)
                .sales(sales)
                .avgOrderValue(MetricsUtils.divide(sales, BigDecimal.valueOf(orders), MetricsUtils.AMOUNT_SCALE))
                .promoOrderCount(promoOrders)
                .promoOrderRate(MetricsUtils.ratio(promoOrders, orders))
                .build();
    }

    // ==================== A17 地域销售分布 ====================

    @Override
    public RegionSalesVO regionSales(StatsQueryDTO query) {
        String[] range = normalize(query);
        List<RegionRow> rows = ordersMapper.regionSales(range[0], range[1], query.getCategory());

        List<RegionSalesVO.RegionVO> items = new ArrayList<>(rows.size());
        int rank = 1;
        for (RegionRow row : rows) {
            items.add(RegionSalesVO.RegionVO.builder()
                    .rank(rank++)
                    .region(row.getRegion())
                    .sales(MetricsUtils.scaleAmount(row.getSales()))
                    .orderCount((int) intValue(row.getOrderCount()))
                    .salesRatio(row.getSalesRatio())
                    .build());
        }
        return RegionSalesVO.builder().items(items).build();
    }

    // ==================== 内部工具 ====================

    /** 缺省最近 30 天并回写到 DTO，供 Mapper 直接使用 */
    private String[] normalize(StatsQueryDTO query) {
        String[] range = DateRangeUtils.normalize(query.getStartDate(), query.getEndDate());
        query.setStartDate(range[0]);
        query.setEndDate(range[1]);
        return range;
    }

    private String[] normalize(String start, String end) {
        return DateRangeUtils.normalize(start, end);
    }

    private long intValue(Number value) {
        return value == null ? 0L : value.longValue();
    }
}