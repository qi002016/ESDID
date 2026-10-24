package com.heima.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * A11 用户分析：一次返回新老对比 + 支付方式占比 + 复购率。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户分析")
public class UserAnalysisVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer totalUsers;

    private Integer newUserCount;

    private Integer oldUserCount;

    private BigDecimal newUserSales;

    private BigDecimal oldUserSales;

    /** 新客客单价 = 新客销售额 / 新客订单数 */
    private BigDecimal newUserAvgOrderValue;

    private BigDecimal oldUserAvgOrderValue;

    /** 新客占比（0~1 小数，分母为总用户数，可为 null） */
    private BigDecimal newUserRate;

    /** 复购率（0~1 小数，分母为总用户数，可为 null） */
    private BigDecimal repurchaseRate;

    private List<PayTypeRateVO> payTypeRates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "支付方式占比")
    public static class PayTypeRateVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private String payType;

        private Integer orderCount;

        /** 以订单数为分母的占比（0~1 小数，4 位） */
        private BigDecimal rate;
    }
}