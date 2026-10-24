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
 * A10 下单时段分布：<b>固定返回 0~23 共 24 个元素</b>，无订单时段补 0。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "下单时段分布")
public class HourlyVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<HourItemVO> hours;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单个时段")
    public static class HourItemVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 0~23 */
        private Integer hour;

        private Integer orderCount;

        private BigDecimal sales;
    }
}