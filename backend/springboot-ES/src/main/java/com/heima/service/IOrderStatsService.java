package com.heima.service;

import com.heima.common.PageResult;
import com.heima.domain.dto.CampaignQueryDTO;
import com.heima.domain.dto.RankingQueryDTO;
import com.heima.domain.dto.StatsQueryDTO;
import com.heima.domain.dto.TrendQueryDTO;
import com.heima.domain.vo.CampaignEffectVO;
import com.heima.domain.vo.CategoryContributionVO;
import com.heima.domain.vo.HourlyVO;
import com.heima.domain.vo.KpiVO;
import com.heima.domain.vo.OrdersStatsVO;
import com.heima.domain.vo.PriceDistributionVO;
import com.heima.domain.vo.RankingVO;
import com.heima.domain.vo.RegionSalesVO;
import com.heima.domain.vo.TrendVO;
import com.heima.domain.vo.UserAnalysisVO;

import java.util.List;

/**
 * 订单统计服务（A3/A4/A5/A7/A8/A9/A10/A11/A15/A17）。
 */
public interface IOrderStatsService {

    /** A3 KPI 汇总 */
    KpiVO kpi(StatsQueryDTO query);

    /** A4 商品 TOP N（rankBy 白名单） */
    List<RankingVO> ranking(RankingQueryDTO query);

    /** A5 时间粒度聚合 */
    OrdersStatsVO stats(StatsQueryDTO query);

    /** A7 趋势对比（环比 / 同比，按桶序号对齐） */
    TrendVO trend(TrendQueryDTO query);

    /** A8 客单价区间分布（固定 6 个区间） */
    PriceDistributionVO priceDistribution(StatsQueryDTO query);

    /** A9 品类贡献（帕累托，仅按日期过滤） */
    CategoryContributionVO categoryContribution(StatsQueryDTO query);

    /** A10 下单时段分布（固定 24 个元素） */
    HourlyVO hourly(StatsQueryDTO query);

    /** A11 用户分析（新老 + 支付方式 + 复购率） */
    UserAnalysisVO userAnalysis(StatsQueryDTO query);

    /** A15 促销活动效果（非活动期 = 活动期前等长区间） */
    CampaignEffectVO campaignEffect(CampaignQueryDTO query);

    /** A17 地域销售分布 */
    RegionSalesVO regionSales(StatsQueryDTO query);
}