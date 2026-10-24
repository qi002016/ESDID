package com.heima.domain.po;

import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.Accessors;

/**
 * <p>
 * 订单表
 * </p>
 *
 * @author qihuang
 * @since 2026-09-16
 */
@Data
@TableName("orders")
@Schema(description="订单表")
public class Orders implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

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

    @Schema(description = "下单时间")
    private LocalDateTime orderTime;

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "new/old")
    private String userType;

    @Schema(description = "支付方式")
    private String payType;

    @Schema(description = "是否促销 0/1")
    private Integer promoFlag;

    @Schema(description = "收货省份")
    private String region;

    private LocalDateTime createdAt;


}
