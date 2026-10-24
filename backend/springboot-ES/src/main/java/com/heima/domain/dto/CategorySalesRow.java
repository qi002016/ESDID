package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 按品类聚合的销售额行（A9 帕累托、A14 达成额共用）。
 */
@Data
public class CategorySalesRow {

    private String category;

    /** A9 使用：销售额 */
    private BigDecimal sales;

    /** A14 使用：周期内达成额 */
    private BigDecimal achieved;
}