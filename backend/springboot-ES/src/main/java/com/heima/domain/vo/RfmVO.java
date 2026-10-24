package com.heima.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * A16 RFM 用户分层：分位（中位数）阈值 + 8 类分层，segments 按 sales 降序。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RFM 用户分层")
public class RfmVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String windowStart;

    private String windowEnd;

    private Thresholds thresholds;

    private Integer totalUsers;

    private BigDecimal totalSales;

    private List<RfmSegmentVO> segments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "中位数阈值")
    public static class Thresholds implements Serializable {

        private static final long serialVersionUID = 1L;

        /** R：距窗口结束日天数的中位数（越小越近） */
        private Integer rDays;

        /** F：下单次数中位数 */
        private Integer fCount;

        /** M：累计金额中位数 */
        private BigDecimal mAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "RFM 分层明细")
    public static class RfmSegmentVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private String segment;

        private Integer userCount;

        private BigDecimal sales;

        /** 销售额占比（0~1 小数，4 位） */
        private BigDecimal salesRatio;

        private BigDecimal avgOrderValue;

        private BigDecimal avgFrequency;
    }
}