package com.heima.domain.vo;

import com.heima.domain.dto.PeriodRow;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * A5 时间粒度聚合结果。
 * <p>区间内无数据时 {@code items} 为空数组（不补零桶）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "时间粒度聚合")
public class OrdersStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "粒度 day/week/month")
    private String granularity;

    @Schema(description = "时间桶列表")
    private List<PeriodRow> items;
}