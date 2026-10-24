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
 * A14 目标达成率。
 * <p>口径：{@code achieved = 周期内实际销售额}；{@code achievementRate = target>0 ? achieved/target : null}；
 * {@code remainingDays = 周期结束日 - 今天}（已结束/非当前周期为 0）；{@code dailyNeeded = remainingDays>0 ? max(0,target-achieved)/remainingDays : 0}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "目标达成率")
public class TargetProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String periodType;

    private String periodValue;

    private BigDecimal totalTarget;

    private BigDecimal achievedSales;

    /** 可为 null（目标为 0 或缺失） */
    private BigDecimal achievementRate;

    private Integer remainingDays;

    private BigDecimal dailyNeeded;

    private List<TargetProgressItemVO> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "分品类达成情况")
    public static class TargetProgressItemVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 品类，ALL 表示总目标 */
        private String category;

        private BigDecimal targetAmount;

        private BigDecimal achieved;

        private BigDecimal achievementRate;
    }
}