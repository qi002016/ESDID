package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * A8 客单价区间聚合行。
 */
@Data
public class PriceIntervalRow {

    /** 区间标签，如 {@code 0-100} */
    private String range;

    private Integer orderCount;

    private BigDecimal sales;
}