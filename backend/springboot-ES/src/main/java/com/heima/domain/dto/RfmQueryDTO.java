package com.heima.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * A16 RFM 用户分层入参。
 * <p>日期缺省为最近 <b>90</b> 天（与其它接口的 30 天不同）。</p>
 */
@Data
@Schema(description = "RFM 分析查询参数")
public class RfmQueryDTO {

    private String category;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "startDate 格式必须为 yyyy-MM-dd")
    private String startDate;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "endDate 格式必须为 yyyy-MM-dd")
    private String endDate;
}