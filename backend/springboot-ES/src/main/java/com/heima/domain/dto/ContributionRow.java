package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * A9 品类贡献聚合行（含窗口函数算出的占比）。
 */
@Data
public class ContributionRow {

    private String category;

    private BigDecimal sales;

    /** 占比（0~1 小数） */
    private BigDecimal salesRatio;

    /** 累计占比（0~1 小数） */
    private BigDecimal cumulativeRatio;
}