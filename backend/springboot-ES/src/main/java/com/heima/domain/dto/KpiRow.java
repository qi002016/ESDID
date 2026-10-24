package com.heima.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A3 KPI 单次聚合结果行（与 XML 中 {@code kpi} 查询的列别名一一对应）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiRow {

    private BigDecimal totalSales;
    private Long totalOrders;
    private BigDecimal avgOrderValue;
    private Integer hotProductCount;
    private Integer totalUsers;
    private Integer newUserCount;
    private Integer repurchaseUsers;
}