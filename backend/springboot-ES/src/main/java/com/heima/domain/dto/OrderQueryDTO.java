package com.heima.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A2 订单分页查询 / A12 CSV 导出 入参。
 * <p>日期缺省为最近 30 天；{@code sort} 走白名单校验（{@code 字段,asc|desc}）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "订单分页/导出查询参数")
public class OrderQueryDTO {

    @Min(value = 1, message = "page 最小为 1")
    @Builder.Default
    private Integer page = 1;

    @Min(value = 1, message = "size 最小为 1")
    @Max(value = 100, message = "size 最大为 100")
    @Builder.Default
    private Integer size = 10;

    @Schema(description = "品类，空表示全部")
    private String category;

    @Schema(description = "订单号或商品名模糊匹配")
    private String keyword;

    @Pattern(regexp = "^(alipay|wechat|card)?$", message = "payType 仅支持 alipay/wechat/card")
    private String payType;

    @Pattern(regexp = "^(new|old)?$", message = "userType 仅支持 new/old")
    private String userType;

    @Min(value = 0, message = "promoFlag 仅支持 0/1")
    @Max(value = 1, message = "promoFlag 仅支持 0/1")
    @Schema(description = "是否促销单 0/1")
    private Integer promoFlag;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "startDate 格式必须为 yyyy-MM-dd")
    private String startDate;

    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "endDate 格式必须为 yyyy-MM-dd")
    private String endDate;

    @Schema(description = "排序：orderTime|totalAmount|price|quantity,asc|desc")
    @Builder.Default
    private String sort = "orderTime,desc";
}