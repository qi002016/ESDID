package com.heima.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * A13 销售目标列表 / A14 目标达成率入参。
 */
@Data
@Schema(description = "销售目标查询参数")
public class TargetQueryDTO {

    @Pattern(regexp = "^(month)?$", message = "periodType 当前仅支持 month")
    @Schema(description = "周期类型，当前仅 month")
    private String periodType;

    @Pattern(regexp = "^(\\d{4}-\\d{2})?$", message = "periodValue 格式必须为 yyyy-MM")
    @Schema(description = "周期值 yyyy-MM，缺省为当前月")
    private String periodValue;

    @Schema(description = "品类，缺省返回全部（含 ALL）")
    private String category;
}