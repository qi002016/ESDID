package com.heima.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * A7 趋势对比返回体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "趋势对比")
public class TrendVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String granularity;

    /** 本期或对比期（previous / previousYear） */
    private String compareType;

    /** 本期区间，供前端展示 */
    private PeriodRange currentPeriod;

    /** 对比期区间，供前端展示 */
    private PeriodRange comparePeriod;

    private List<TrendItemVO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "区间起止")
    public static class PeriodRange implements Serializable {

        private static final long serialVersionUID = 1L;

        private String startDate;

        private String endDate;
    }
}