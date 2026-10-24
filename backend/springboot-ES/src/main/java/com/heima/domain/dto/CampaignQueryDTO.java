package com.heima.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * A15 促销活动效果入参：活动期起止日期<b>必填</b>。
 */
@Data
@Schema(description = "促销活动效果查询参数")
public class CampaignQueryDTO {

    @NotBlank(message = "startDate 不能为空")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "startDate 格式必须为 yyyy-MM-dd")
    private String startDate;

    @NotBlank(message = "endDate 不能为空")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "endDate 格式必须为 yyyy-MM-dd")
    private String endDate;

    private String category;
}