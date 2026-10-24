package com.heima.service;

import com.heima.domain.dto.RfmQueryDTO;
import com.heima.domain.vo.RfmVO;

/**
 * A16 RFM 用户分层服务。
 */
public interface IRfmService {

    /** RFM 分层（阈值取中位数，8 类二分组合） */
    RfmVO analyze(RfmQueryDTO query);
}