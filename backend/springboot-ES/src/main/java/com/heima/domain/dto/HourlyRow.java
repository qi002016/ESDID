package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * A10 下单时段聚合行（{@code hour} 为 0~23 整数）。
 */
@Data
public class HourlyRow {

    private Integer hour;

    private Integer orderCount;

    private BigDecimal sales;
}