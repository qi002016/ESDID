package com.heima.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.heima.domain.dto.CategorySalesRow;
import com.heima.domain.dto.TargetQueryDTO;
import com.heima.domain.vo.TargetListVO;
import com.heima.domain.vo.TargetProgressVO;
import com.heima.mapper.OrdersMapper;
import com.heima.mapper.SalesTargetMapper;
import com.heima.service.ISalesTargetService;
import com.heima.util.DateRangeUtils;
import com.heima.util.MetricsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售目标服务实现（A13/A14）。
 */
@Slf4j
@Service
public class SalesTargetServiceImpl implements ISalesTargetService {

    /** 总目标行的固定品类标识 */
    private static final String ALL = "ALL";

    private static final String PERIOD_TYPE_MONTH = "month";

    private final SalesTargetMapper salesTargetMapper;

    private final OrdersMapper ordersMapper;

    private final Cache<String, TargetListVO> targetListCache;

    public SalesTargetServiceImpl(SalesTargetMapper salesTargetMapper,
                                  OrdersMapper ordersMapper,
                                  @Qualifier("targetListCache") Cache<String, TargetListVO> targetListCache) {
        this.salesTargetMapper = salesTargetMapper;
        this.ordersMapper = ordersMapper;
        this.targetListCache = targetListCache;
    }

    // ==================== A13 目标列表 ====================

    @Override
    public TargetListVO listTargets(TargetQueryDTO query) {
        String periodType = normalizePeriodType(query.getPeriodType());
        String periodValue = normalizePeriodValue(query.getPeriodValue());
        String cacheKey = periodType + '|' + periodValue + '|' + (query.getCategory() == null ? "" : query.getCategory());

        TargetListVO cached = targetListCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<TargetListVO.TargetVO> items = salesTargetMapper.listTargets(periodType, periodValue, query.getCategory());
        TargetListVO vo = TargetListVO.builder()
                .periodType(periodType)
                .periodValue(periodValue)
                .items(items)
                .build();
        targetListCache.put(cacheKey, vo);
        return vo;
    }

    @Override
    public void evictTargetCache() {
        targetListCache.invalidateAll();
    }

    // ==================== A14 目标达成率 ====================

    @Override
    public TargetProgressVO progress(TargetQueryDTO query) {
        String periodType = normalizePeriodType(query.getPeriodType());
        String periodValue = normalizePeriodValue(query.getPeriodValue());
        String category = query.getCategory();

        LocalDate periodStart = DateRangeUtils.monthStart(periodValue);
        LocalDate periodEnd = DateRangeUtils.monthEnd(periodValue);

        // 周期内实际达成额（与筛选无关地按品类分组）
        List<CategorySalesRow> achievedRows =
                ordersMapper.achievedByCategory(periodStart.toString(), periodEnd.toString());
        Map<String, BigDecimal> achievedMap = new HashMap<>(achievedRows.size());
        BigDecimal allAchieved = BigDecimal.ZERO;
        for (CategorySalesRow row : achievedRows) {
            BigDecimal achieved = MetricsUtils.nvl(row.getAchieved());
            achievedMap.put(row.getCategory(), achieved);
            allAchieved = allAchieved.add(achieved);
        }

        List<TargetListVO.TargetVO> targets =
                salesTargetMapper.listTargets(periodType, periodValue, category);

        // 非当前月（或已结束周期）剩余天数按 0 处理
        boolean currentMonth = periodValue.equals(YearMonth.now().toString());
        int remainingDays = 0;
        if (currentMonth) {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), periodEnd);
            remainingDays = (int) Math.max(0, days);
        }

        BigDecimal totalTarget;
        BigDecimal achievedSales;
        List<TargetProgressVO.TargetProgressItemVO> items = new ArrayList<>();

        if (StringUtils.hasText(category)) {
            // 指定品类：只返回该品类（category=ALL 时代表总计）
            totalTarget = findTarget(targets, category);
            achievedSales = ALL.equalsIgnoreCase(category)
                    ? allAchieved : achievedMap.getOrDefault(category, BigDecimal.ZERO);
            items.add(buildItem(category, totalTarget, achievedSales));
        } else {
            totalTarget = findTarget(targets, ALL);
            achievedSales = allAchieved;
            // items 必须同时包含 ALL 与 sales_target 里每个品类
            items.add(buildItem(ALL, totalTarget, allAchieved));
            for (TargetListVO.TargetVO target : targets) {
                if (ALL.equalsIgnoreCase(target.getCategory())) {
                    continue;
                }
                BigDecimal achieved = achievedMap.getOrDefault(target.getCategory(), BigDecimal.ZERO);
                items.add(buildItem(target.getCategory(), MetricsUtils.nvl(target.getTargetAmount()), achieved));
            }
        }

        return TargetProgressVO.builder()
                .periodType(periodType)
                .periodValue(periodValue)
                .totalTarget(MetricsUtils.scaleAmount(totalTarget))
                .achievedSales(MetricsUtils.scaleAmount(achievedSales))
                // 目标为 0（或目标缺失）时达成率为 null
                .achievementRate(MetricsUtils.ratio(achievedSales, totalTarget))
                .remainingDays(remainingDays)
                .dailyNeeded(dailyNeeded(totalTarget, achievedSales, remainingDays))
                .items(items)
                .build();
    }

    private TargetProgressVO.TargetProgressItemVO buildItem(String category, BigDecimal target, BigDecimal achieved) {
        BigDecimal targetAmount = MetricsUtils.scaleAmount(target);
        BigDecimal achievedAmount = MetricsUtils.scaleAmount(achieved);
        return TargetProgressVO.TargetProgressItemVO.builder()
                .category(category)
                .targetAmount(targetAmount)
                .achieved(achievedAmount)
                .achievementRate(MetricsUtils.ratio(achievedAmount, targetAmount))
                .build();
    }

    /** 剩余天数内每天需要的销售额；剩余天数为 0 时返回 0.00 */
    private BigDecimal dailyNeeded(BigDecimal target, BigDecimal achieved, int remainingDays) {
        if (remainingDays <= 0) {
            return MetricsUtils.scaleAmount(BigDecimal.ZERO);
        }
        BigDecimal gap = MetricsUtils.nvl(target).subtract(MetricsUtils.nvl(achieved));
        if (gap.signum() <= 0) {
            return MetricsUtils.scaleAmount(BigDecimal.ZERO);
        }
        return gap.divide(BigDecimal.valueOf(remainingDays), MetricsUtils.AMOUNT_SCALE, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal findTarget(List<TargetListVO.TargetVO> targets, String category) {
        for (TargetListVO.TargetVO target : targets) {
            if (category.equalsIgnoreCase(target.getCategory())) {
                return MetricsUtils.nvl(target.getTargetAmount());
            }
        }
        // 目标缺失：targetAmount = 0.00、achievementRate = null、dailyNeeded = 0.00
        return MetricsUtils.scaleAmount(BigDecimal.ZERO);
    }

    private String normalizePeriodType(String periodType) {
        return StringUtils.hasText(periodType) ? periodType.trim().toLowerCase() : PERIOD_TYPE_MONTH;
    }

    private String normalizePeriodValue(String periodValue) {
        return StringUtils.hasText(periodValue) ? periodValue.trim() : YearMonth.now().toString();
    }
}