package com.heima.service.impl;

import com.heima.domain.dto.RfmQueryDTO;
import com.heima.domain.dto.RfmUserRow;
import com.heima.domain.vo.RfmVO;
import com.heima.mapper.OrdersMapper;
import com.heima.service.IRfmService;
import com.heima.util.DateRangeUtils;
import com.heima.util.MetricsUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A16 RFM 用户分层实现。
 * <p>阈值用<b>中位数</b>（分位法，抗极值）；R/F/M 各二分后组合 8 类；
 * 用户量通常在 10 万以内，直接取明细在 Java 侧聚合，比 SQL 里算中位数更清晰、更易单测。</p>
 */
@Service
@RequiredArgsConstructor
public class RfmServiceImpl implements IRfmService {

    /** A16 缺省窗口：最近 90 天（与其它接口的 30 天不同） */
    private static final int DEFAULT_WINDOW_DAYS = 90;

    /** key 顺序为 R-F-M，1 = 高（R 高表示最近有购买） */
    private static final Map<String, String> SEGMENT_NAMES = Map.of(
            "111", "重要价值客户",
            "011", "重要保持客户",
            "101", "重要发展客户",
            "001", "重要挽留客户",
            "110", "一般价值客户",
            "010", "一般保持客户",
            "100", "一般发展客户",
            "000", "流失客户"
    );

    private final OrdersMapper ordersMapper;

    @Override
    public RfmVO analyze(RfmQueryDTO query) {
        String[] range = DateRangeUtils.normalize(query.getStartDate(), query.getEndDate(), DEFAULT_WINDOW_DAYS);
        List<RfmUserRow> users = ordersMapper.rfmUsers(range[0], range[1], query.getCategory(), range[1]);

        if (users.isEmpty()) {
            return RfmVO.builder()
                    .windowStart(range[0])
                    .windowEnd(range[1])
                    .thresholds(null)
                    .totalUsers(0)
                    .totalSales(BigDecimal.ZERO)
                    .segments(new ArrayList<>())
                    .build();
        }

        // 中位数阈值
        Integer rThreshold = medianInt(users.stream().map(RfmUserRow::getRecencyDays).toList());
        Integer fThreshold = medianInt(users.stream().map(RfmUserRow::getFrequency).toList());
        BigDecimal mThreshold = medianDecimal(users.stream().map(RfmUserRow::getMonetary).toList());

        // 分层聚合
        Map<String, SegmentAccumulator> accumulators = new LinkedHashMap<>();
        BigDecimal totalSales = BigDecimal.ZERO;
        for (RfmUserRow user : users) {
            String key = (user.getRecencyDays() != null && user.getRecencyDays() <= rThreshold ? "1" : "0")
                    + (user.getFrequency() != null && user.getFrequency() >= Math.max(1, fThreshold) ? "1" : "0")
                    + (user.getMonetary() != null && user.getMonetary().compareTo(mThreshold) >= 0 ? "1" : "0");
            SegmentAccumulator acc = accumulators.computeIfAbsent(key, k -> new SegmentAccumulator());
            acc.userCount++;
            acc.sales = acc.sales.add(MetricsUtils.nvl(user.getMonetary()));
            acc.frequency += user.getFrequency() == null ? 0 : user.getFrequency();
            totalSales = totalSales.add(MetricsUtils.nvl(user.getMonetary()));
        }

        List<RfmVO.RfmSegmentVO> segments = new ArrayList<>(accumulators.size());
        for (Map.Entry<String, SegmentAccumulator> entry : accumulators.entrySet()) {
            SegmentAccumulator acc = entry.getValue();
            BigDecimal sales = MetricsUtils.scaleAmount(acc.sales);
            segments.add(RfmVO.RfmSegmentVO.builder()
                    .segment(SEGMENT_NAMES.getOrDefault(entry.getKey(), "其他"))
                    .userCount(acc.userCount)
                    .sales(sales)
                    .salesRatio(MetricsUtils.ratio(sales, totalSales))
                    .avgOrderValue(MetricsUtils.divide(sales, BigDecimal.valueOf(acc.userCount),
                            MetricsUtils.AMOUNT_SCALE))
                    .avgFrequency(MetricsUtils.divide(BigDecimal.valueOf(acc.frequency),
                            BigDecimal.valueOf(acc.userCount), MetricsUtils.AMOUNT_SCALE))
                    .build());
        }
        // 按销售额降序
        segments.sort(Comparator.comparing(RfmVO.RfmSegmentVO::getSales,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return RfmVO.builder()
                .windowStart(range[0])
                .windowEnd(range[1])
                .thresholds(RfmVO.Thresholds.builder()
                        .rDays(rThreshold)
                        .fCount(fThreshold)
                        .mAmount(mThreshold)
                        .build())
                .totalUsers(users.size())
                .totalSales(MetricsUtils.scaleAmount(totalSales))
                .segments(segments)
                .build();
    }

    /** 整数中位数：偶数个取中间两个的均值（向下取整） */
    private Integer medianInt(List<Integer> values) {
        List<Integer> sorted = values.stream().filter(java.util.Objects::nonNull).sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    /** 金额中位数：偶数个取中间两个的均值（保留 2 位） */
    private BigDecimal medianDecimal(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().filter(java.util.Objects::nonNull).sorted().toList();
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int size = sorted.size();
        if (size % 2 == 1) {
            return MetricsUtils.scaleAmount(sorted.get(size / 2));
        }
        return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                .divide(BigDecimal.valueOf(2), MetricsUtils.AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /** 单类累加器 */
    private static final class SegmentAccumulator {
        private int userCount;
        private BigDecimal sales = BigDecimal.ZERO;
        private long frequency;
    }
}