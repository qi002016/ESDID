package com.heima.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 统计类接口通用入参（《后端开发文档》§4.1）：
 * A3 KPI、A5 粒度聚合、A8 客单价分布、A10 时段分布、A11 用户分析、A17 地域分布。
 */
@Data
@Schema(description = "统计通用查询参数")
public class StatsQueryDTO {

    @Schema(description = "品类，空表示全部")
    private String category;

    @Pattern(regexp = "^(day|week|month)?$", message = "granularity 仅支持 day/week/month")
    @Schema(description = "时间粒度（A5/A7 使用），默认 day")
    private String granularity;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "startDate 格式必须为 yyyy-MM-dd")
    private String startDate;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "endDate 格式必须为 yyyy-MM-dd")
    private String endDate;
}