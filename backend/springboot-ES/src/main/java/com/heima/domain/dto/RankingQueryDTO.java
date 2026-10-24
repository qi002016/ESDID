package com.heima.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * A4 商品排行入参。
 */
@Data
@Schema(description = "商品排行查询参数")
public class RankingQueryDTO {

    @Pattern(regexp = "^(sales|quantity)?$", message = "rankBy 仅支持 sales/quantity")
    @Schema(description = "排序口径，默认 sales")
    private String rankBy;

    private String category;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "startDate 格式必须为 yyyy-MM-dd")
    private String startDate;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "endDate 格式必须为 yyyy-MM-dd")
    private String endDate;

    @Min(value = 1, message = "limit 最小为 1")
    @Max(value = 50, message = "limit 最大为 50")
    @Schema(description = "返回条数，默认 10")
    private Integer limit;
}