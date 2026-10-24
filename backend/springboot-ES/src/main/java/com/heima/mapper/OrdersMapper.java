package com.heima.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.domain.dto.CategorySalesRow;
import com.heima.domain.dto.CampaignMetricsRow;
import com.heima.domain.dto.ContributionRow;
import com.heima.domain.dto.HourlyRow;
import com.heima.domain.dto.KpiRow;
import com.heima.domain.dto.OrderQueryDTO;
import com.heima.domain.dto.PeriodRow;
import com.heima.domain.dto.PriceIntervalRow;
import com.heima.domain.dto.RankingQueryDTO;
import com.heima.domain.dto.RegionRow;
import com.heima.domain.dto.RfmUserRow;
import com.heima.domain.dto.UserTypeAggRow;
import com.heima.domain.po.Orders;
import com.heima.domain.vo.CategoryVO;
import com.heima.domain.vo.OrderVO;
import com.heima.domain.vo.RankingVO;
import com.heima.domain.vo.UserAnalysisVO;
import com.heima.util.Granularity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;


import java.util.List;

/**
 * 订单表 Mapper（A1~A12、A14~A17 的聚合 SQL，见 {@code resources/mapper/OrdersMapper.xml}）。
 */
@Mapper
public interface OrdersMapper extends BaseMapper<Orders> {

    /** A1 品类列表：{@code category → COUNT(DISTINCT product_name)} */
    List<CategoryVO> countProductsByCategory();

    /** A2 总记录数（与 pageOrders 使用同一套条件） */
    long countOrders(@Param("q") OrderQueryDTO q);

    /**
     * A2 分页记录。
     *
     * @param sortColumn    白名单校验后的列名
     * @param sortDirection 白名单校验后的方向
     */
    List<OrderVO> pageOrders(@Param("q") OrderQueryDTO q,
                             @Param("sortColumn") String sortColumn,
                             @Param("sortDirection") String sortDirection,
                             @Param("offset") int offset,
                             @Param("size") int size);

    /** A6 订单详情（按订单号查询） */
    OrderVO getByOrderId(@Param("orderId") String orderId);

    /** A3 一次查询拿到全部 KPI 指标 */
    KpiRow kpi(@Param("startDate") String startDate,
               @Param("endDate") String endDate,
               @Param("category") String category);

    /**
     * A4 商品排行。
     *
     * @param orderBy 仅允许 {@code sales} / {@code quantity}
     */
    List<RankingVO> productRanking(@Param("q") RankingQueryDTO q,
                                   @Param("orderBy") String orderBy,
                                   @Param("limit") int limit);

    /** A5 时间粒度聚合（粒度表达式来自 {@link Granularity} 常量） */
    List<PeriodRow> orderStats(@Param("granularity") Granularity granularity,
                               @Param("startDate") String startDate,
                               @Param("endDate") String endDate,
                               @Param("category") String category);

    /** A8 客单价区间分布 */
    List<PriceIntervalRow> priceDistribution(@Param("startDate") String startDate,
                                             @Param("endDate") String endDate,
                                             @Param("category") String category);

    /** A9 品类贡献（窗口函数算占比与累计占比） */
    List<ContributionRow> categoryContribution(@Param("startDate") String startDate,
                                               @Param("endDate") String endDate);

    /** A14 周期内各品类达成额 */
    List<CategorySalesRow> achievedByCategory(@Param("periodStart") String periodStart,
                                             @Param("periodEnd") String periodEnd);

    /** A10 下单时段分布 */
    List<HourlyRow> hourly(@Param("startDate") String startDate,
                           @Param("endDate") String endDate,
                           @Param("category") String category);

    /** A11 新老用户维度聚合 */
    List<UserTypeAggRow> userTypeAgg(@Param("startDate") String startDate,
                                     @Param("endDate") String endDate,
                                     @Param("category") String category);

    /** A11 支付方式维度聚合（rate 以订单数为分母） */
    List<UserAnalysisVO.PayTypeRateVO> payTypeRates(@Param("startDate") String startDate,
                                                    @Param("endDate") String endDate,
                                                    @Param("category") String category);

    /** A3/A11 期内下单用户数（去重） */
    Integer distinctUserCount(@Param("startDate") String startDate,
                              @Param("endDate") String endDate,
                              @Param("category") String category);

    /** A3/A11 期内复购用户数（下单 ≥2 次） */
    Integer repurchaseUserCount(@Param("startDate") String startDate,
                                @Param("endDate") String endDate,
                                @Param("category") String category);

    /** A17 地域销售分布 */
    List<RegionRow> regionSales(@Param("startDate") String startDate,
                                @Param("endDate") String endDate,
                                @Param("category") String category);

    /** A15 活动期/非活动期核心指标 */
    CampaignMetricsRow campaignMetrics(@Param("startDate") String startDate,
                                       @Param("endDate") String endDate,
                                       @Param("category") String category);

    /** A16 RFM 单用户明细 */
    List<RfmUserRow> rfmUsers(@Param("startDate") String startDate,
                              @Param("endDate") String endDate,
                              @Param("category") String category,
                              @Param("windowEnd") String windowEnd);

    /** A12 导出游标分页（id &gt; lastId，避免 OFFSET 深翻页） */
    List<Orders> exportBatch(@Param("q") OrderQueryDTO q,
                             @Param("lastId") long lastId,
                             @Param("size") int size);

    /** A18 批量写入（500 行一次 INSERT ... VALUES (...),(...)） */
    int batchInsert(@Param("list") List<Orders> list);

    /** A18 覆盖导入：清空订单表（DELETE 而非 TRUNCATE，保证与写入同事务） */
    int deleteAllOrders();
}