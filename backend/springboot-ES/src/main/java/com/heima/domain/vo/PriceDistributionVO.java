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
 * A8 客单价区间分布：区间固定 6 个，无数据的区间补 0 以保证直方图形状稳定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "客单价区间分布")
public class PriceDistributionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer totalOrders;

    private BigDecimal totalSales;

    private List<PriceIntervalVO> intervals;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单个客单价区间")
    public static class PriceIntervalVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 区间标签，如 0-100 / 5000以上 */
        private String range;

        private Integer orderCount;

        private BigDecimal sales;

        /** 订单占比（0~1 小数，4 位） */
        private BigDecimal orderRatio;
    }
}