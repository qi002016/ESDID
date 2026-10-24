package com.heima.controller;

import com.heima.common.Result;
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
import com.heima.service.IOrderStatsService;
import com.heima.service.ISalesTargetService;
import com.heima.domain.dto.TargetQueryDTO;
import com.heima.domain.vo.TargetProgressVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单统计接口：A3/A4/A5/A7/A8/A9/A10/A11/A14/A15/A17。
 * <p>所有接口缺省日期为最近 30 天，比率返回 0~1 小数，无法计算时返回 {@code null}。</p>
 */
@Tag(name = "订单与统计", description = "A3~A11、A14、A15、A17 统计接口")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderStatsController {

    private final IOrderStatsService orderStatsService;

    private final ISalesTargetService salesTargetService;

    // ==================== A3 KPI 汇总 ====================

    @Operation(summary = "A3 KPI 汇总（销售额/订单数/客单价/热销商品数/用户数/新客率/复购率）")
    @GetMapping("/kpi")
    public Result<KpiVO> kpi(@Valid StatsQueryDTO query) {
        return Result.ok(orderStatsService.kpi(query));
    }

    // ==================== A4 商品 TOP N ====================

    @Operation(summary = "A4 商品排行（rankBy=sales|quantity，limit<=50）")
    @GetMapping("/ranking")
    public Result<List<RankingVO>> ranking(@Valid RankingQueryDTO query) {
        return Result.ok(orderStatsService.ranking(query));
    }

    // ==================== A5 时间粒度聚合 ====================

    @Operation(summary = "A5 时间粒度聚合（granularity=day|week|month）")
    @GetMapping("/stats")
    public Result<OrdersStatsVO> stats(@Valid StatsQueryDTO query) {
        return Result.ok(orderStatsService.stats(query));
    }

    // ==================== A7 趋势对比 ====================

    @Operation(summary = "A7 趋势对比（compareType=previous|previousYear，按桶序号对齐）")
    @GetMapping("/trend")
    public Result<TrendVO> trend(@Valid TrendQueryDTO query) {
        return Result.ok(orderStatsService.trend(query));
    }

    // ==================== A8 客单价区间分布 ====================

    @Operation(summary = "A8 客单价区间分布（固定 6 个区间）")
    @GetMapping("/price-distribution")
    public Result<PriceDistributionVO> priceDistribution(@Valid StatsQueryDTO query) {
        return Result.ok(orderStatsService.priceDistribution(query));
    }

    // ==================== A9 品类贡献（帕累托） ====================

    @Operation(summary = "A9 品类贡献（帕累托，仅按日期过滤，无 category 参数）")
    @GetMapping("/category-contribution")
    public Result<CategoryContributionVO> categoryContribution(@Valid StatsQueryDTO query) {
        return Result.ok(orderStatsService.categoryContribution(query));
    }

    // ==================== A10 下单时段分布 ====================

    @Operation(summary = "A10 下单时段分布（固定返回 0~23 共 24 个元素）")
    @GetMapping("/hourly")
    public Result<HourlyVO> hourly(@Valid StatsQueryDTO query) {
        return Result.ok(orderStatsService.hourly(query));
    }

    // ==================== A11 用户分析 ====================

    @Operation(summary = "A11 用户分析（新老对比 + 支付方式占比 + 复购率）")
    @GetMapping("/user-analysis")
    public Result<UserAnalysisVO> userAnalysis(@Valid StatsQueryDTO query) {
        return Result.ok(orderStatsService.userAnalysis(query));
    }

    // ==================== A14 目标达成率 ====================

    @Operation(summary = "A14 目标达成率（目标缺失时 achievementRate=null、dailyNeeded=0）")
    @GetMapping("/target-progress")
    public Result<TargetProgressVO> targetProgress(@Valid TargetQueryDTO query) {
        return Result.ok(salesTargetService.progress(query));
    }

    // ==================== A15 促销活动效果 ====================

    @Operation(summary = "A15 促销活动效果（startDate/endDate 必填，非活动期=活动期前等长区间）")
    @GetMapping("/campaign-effect")
    public Result<CampaignEffectVO> campaignEffect(@Valid CampaignQueryDTO query) {
        return Result.ok(orderStatsService.campaignEffect(query));
    }

    // ==================== A17 地域销售分布 ====================

    @Operation(summary = "A17 地域销售分布（region 原样返回，不做名称翻译）")
    @GetMapping("/region")
    public Result<RegionSalesVO> region(@Valid StatsQueryDTO query) {
        return Result.ok(orderStatsService.regionSales(query));
    }
}