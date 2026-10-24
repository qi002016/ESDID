package com.heima.domain.po;

import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 销售目标表
 * </p>
 *
 * @author qihuang
 * @since 2026-09-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sales_target")
@Schema(description="销售目标表")
public class SalesTarget implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "周期类型 month/quarter/year")
    private String periodType;

    @Schema(description = "周期值 如 2017-01")
    private String periodValue;

    @Schema(description = "品类")
    private String category;

    @Schema(description = "目标金额")
    private BigDecimal targetAmount;


}
