package com.heima.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * A9 品类贡献（帕累托）：销售额 + 占比 + 累计占比。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "品类贡献（帕累托）")
public class CategoryContributionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private BigDecimal totalSales;

    private List<ContributionItemVO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "品类贡献项")
    public static class ContributionItemVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 排名（1 开始，按销售额降序） */
        private Integer rank;

        private String category;

        private BigDecimal sales;

        /** 占比（0~1 小数，4 位） */
        private BigDecimal salesRatio;

        /** 累计占比（0~1 小数，4 位），末项为 1 */
        private BigDecimal cumulativeRatio;
    }
}