package com.heima.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A5/A7 通用时间桶聚合行：{@code period → (sales, orders)}。
 * <p>{@code period} 为字符串，日 {@code 2026-09-01}、ISO 周 {@code 2026-W38}、月 {@code 2026-09}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodRow {

    private String period;

    private BigDecimal sales;

    private Long orders;
}