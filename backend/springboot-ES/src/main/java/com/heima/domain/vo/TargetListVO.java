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
 * A13 销售目标列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "销售目标列表")
public class TargetListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String periodType;

    private String periodValue;

    private List<TargetVO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "销售目标项")
    public static class TargetVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;

        /** 品类，ALL 表示总目标 */
        private String category;

        private BigDecimal targetAmount;
    }
}