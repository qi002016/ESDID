package com.heima.service;

import com.heima.domain.dto.TargetQueryDTO;
import com.heima.domain.vo.TargetListVO;
import com.heima.domain.vo.TargetProgressVO;

/**
 * 销售目标服务（A13/A14）。
 */
public interface ISalesTargetService {

    /** A13 销售目标列表（Caffeine 缓存，TTL 5 分钟） */
    TargetListVO listTargets(TargetQueryDTO query);

    /** A14 目标达成率 */
    TargetProgressVO progress(TargetQueryDTO query);

    /** 目标数据变更后主动失效缓存 */
    void evictTargetCache();
}