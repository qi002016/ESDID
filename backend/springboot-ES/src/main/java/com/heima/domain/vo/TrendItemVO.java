package com.heima.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A7 趋势对比单项：本期与对比期<b>按桶序号对齐</b>后的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "趋势对比项")
public class TrendItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 展示用的桶标签（取本期标签） */
    private String period;

    private BigDecimal currentSales;

    private Integer currentOrders;

    /** 对比期缺失时为 null */
    private BigDecimal prevSales;

    private Integer prevOrders;

    /** null 表示数据不足（对比期为 0 或无数据），不返回 0 */
    private BigDecimal salesGrowthRate;

    private BigDecimal ordersGrowthRate;
}