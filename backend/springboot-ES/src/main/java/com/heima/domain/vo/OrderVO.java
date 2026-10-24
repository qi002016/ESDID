package com.heima.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A2 / A6 订单数据（字段与数据字典完全同名）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "订单")
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单号")
    private String orderId;

    @Schema(description = "商品名")
    private String productName;

    @Schema(description = "品类")
    private String category;

    @Schema(description = "单价")
    private BigDecimal price;

    @Schema(description = "数量")
    private Integer quantity;

    @Schema(description = "订单总金额")
    private BigDecimal totalAmount;

    /** 前端按 yyyy-MM-dd HH:mm:ss 原样渲染，不能是 ISO-8601 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @Schema(description = "下单时间 yyyy-MM-dd HH:mm:ss")
    private LocalDateTime orderTime;

    @Schema(description = "用户 ID")
    private String userId;

    @Schema(description = "new / old")
    private String userType;

    @Schema(description = "alipay / wechat / card")
    private String payType;

    @Schema(description = "是否促销单 0/1")
    private Integer promoFlag;

    @Schema(description = "收货省份")
    private String region;
}