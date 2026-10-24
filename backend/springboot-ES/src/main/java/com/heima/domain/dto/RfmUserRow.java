package com.heima.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * A16 RFM 单用户明细行（在 Java 侧算中位数阈值与分层）。
 */
@Data
public class RfmUserRow {

    private String userId;

    /** F：下单次数 */
    private Integer frequency;

    /** M：累计金额 */
    private BigDecimal monetary;

    /** R：距窗口结束日的天数（越小越近） */
    private Integer recencyDays;
}