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
public class RankingVO {
    /**
     * 排名
     */
    private Integer rank;
    /**
     * 商品名称
     */
    private String productName;
    /**
     * 销售额
     */
    private BigDecimal sales;
    /**
     * 销量
     */
    private Integer quantity;
}
