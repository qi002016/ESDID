package com.heima.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.domain.po.DailySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 每日预聚合 Mapper（P1：{@code daily_summary}）。
 */
@Mapper
public interface DailySummaryMapper extends BaseMapper<DailySummary> {

    /** 删除某天的预聚合结果（支持补跑） */
    int deleteByDate(@Param("statDate") LocalDate statDate);

    /** INSERT ... SELECT 聚合写入某天数据（品类 / 支付方式 / 用户类型 / 总计 四个维度组合） */
    int insertFromOrders(@Param("statDate") LocalDate statDate);
}