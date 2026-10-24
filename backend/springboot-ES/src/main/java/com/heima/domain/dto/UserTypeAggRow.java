package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * A11 新老用户维度聚合行。
 */
@Data
public class UserTypeAggRow {

    /** new / old */
    private String userType;

    private Integer userCount;

    private BigDecimal sales;

    private Long orderCount;
}