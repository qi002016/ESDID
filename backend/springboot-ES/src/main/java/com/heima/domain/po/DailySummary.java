package com.heima.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日预聚合（P1 工程化增强，表 {@code daily_summary}）。
 */
@Data
@TableName("daily_summary")
@Schema(description = "每日预聚合")
public class DailySummary implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;

    private String category;

    private String payType;

    private String userType;

    private Integer orderCount;

    private BigDecimal totalSales;

    private Integer userCount;
}