package com.heima.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.domain.po.SalesTarget;
import com.heima.domain.vo.TargetListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 销售目标 Mapper（A13/A14）。
 */
@Mapper
public interface SalesTargetMapper extends BaseMapper<SalesTarget> {

    /**
     * A13 目标列表：ALL 总目标排最前，其余按目标额降序。
     */
    List<TargetListVO.TargetVO> listTargets(@Param("periodType") String periodType,
                                            @Param("periodValue") String periodValue,
                                            @Param("category") String category);
}