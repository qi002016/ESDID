package com.heima.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * A7 趋势对比入参。
 */
@Data
@Schema(description = "趋势对比查询参数")
public class TrendQueryDTO {

    @Pattern(regexp = "^(day|week|month)?$", message = "granularity 仅支持 day/week/month")
    @Schema(description = "粒度，默认 day")
    private String granularity;

    @Pattern(regexp = "^(previous|previousYear)?$", message = "compareType 仅支持 previous/previousYear")
    @Schema(description = "对比方式，默认 previous（环比）")
    private String compareType;

    private String category;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "startDate 格式必须为 yyyy-MM-dd")
    private String startDate;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "endDate 格式必须为 yyyy-MM-dd")
    private String endDate;

    @Pattern(regexp = "^(alipay|wechat|card)?$", message = "payType 仅支持 alipay/wechat/card")
    @Schema(description = "支付方式，空表示全部")
    private String payType;

    @Pattern(regexp = "^(new|old)?$", message = "userType 仅支持 new/old")
    @Schema(description = "用户类型，空表示全部")
    private String userType;

    @Schema(description = "促销标记 0/1，空表示全部")
    private Integer promoFlag;
}