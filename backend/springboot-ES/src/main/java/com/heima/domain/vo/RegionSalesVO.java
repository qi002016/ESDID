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
 * A17 地域销售分布（{@code region} 原样返回，后端不做名称翻译）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "地域销售分布")
public class RegionSalesVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<RegionVO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "地域销售项")
    public static class RegionVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 排名（1 开始） */
        private Integer rank;

        private String region;

        private BigDecimal sales;

        private Integer orderCount;

        /** 占比（0~1 小数，4 位） */
        private BigDecimal salesRatio;
    }
}