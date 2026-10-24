package com.heima.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiVO {
    /**
     * 总销售额
     */
    private BigDecimal totalSales;

    /**
     * 订单总数
     */
    private Integer totalOrders;

    /**
     * 平均订单金额
     */
    private BigDecimal avgOrderValue;

    /**
     * 热销商品数量
     */
    private Integer hotProductCount;

    /**
     * 用户总数
     */
    private Integer totalUsers;

    /**
     * 新用户占比
     */
    private BigDecimal newUserRate;

    /**
     * 复购率
     */
    private BigDecimal repurchaseRate;
}
