package com.heima.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A15 促销活动效果：活动期 = 传入区间；<b>非活动期 = 活动期前等长区间</b>。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "促销活动效果")
public class CampaignEffectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String activityPeriodStart;

    private String activityPeriodEnd;

    private String nonActivityPeriodStart;

    private String nonActivityPeriodEnd;

    private CampaignMetricsVO activity;

    private CampaignMetricsVO nonActivity;

    /** 销售额增长率（对比期分母为 0 时 null） */
    private BigDecimal salesGrowthRate;

    /** 订单量增长率（对比期分母为 0 时 null） */
    private BigDecimal ordersGrowthRate;

    /** 促销拉动率 = 活动期促销单销售额 / 活动期销售额（无促销单时 null） */
    private BigDecimal promotionPullRate;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "活动期指标")
    public static class CampaignMetricsVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long orders;

        private BigDecimal sales;

        private BigDecimal avgOrderValue;

        private Long promoOrderCount;

        /** 促销单占比（0~1 小数，4 位，可为 null） */
        private BigDecimal promoOrderRate;
    }
}