package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * A17 地域销售分布聚合行。
 */
@Data
public class RegionRow {

    private String region;

    private BigDecimal sales;

    private Integer orderCount;

    /** 占比（0~1 小数，SQL 窗口函数算出） */
    private BigDecimal salesRatio;
}