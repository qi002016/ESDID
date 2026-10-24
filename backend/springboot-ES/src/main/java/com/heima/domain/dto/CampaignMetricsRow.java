package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * A15 促销活动/非活动期的核心指标行。
 */
@Data
public class CampaignMetricsRow {

    private Long orders;

    private BigDecimal sales;

    /** 促销单数量（promo_flag = 1） */
    private Long promoOrderCount;

    /** 促销单销售额 */
    private BigDecimal promoSales;
}