import request from '@/utils/request'

/**
 * 全局筛选透传说明：
 * 文档中本文件各接口仅声明 category / startDate / endDate，
 * 但筛选栏的「支付方式 / 用户类型 / 促销标记」属全局筛选，由 store.statParams 一并透传。
 * 后端未实现对应过滤时会忽略未知参数，不影响兼容性。
 */

/**
 * A4 商品 TOP10 排行
 * GET /api/orders/ranking
 * 参数：rankBy(sales|quantity), category, startDate, endDate, limit(最大50)
 * 返回：[{ rank, productName, sales, quantity }]
 */
export function getProductRanking(params) {
  return request.get('/orders/ranking', { params })
}

/**
 * A5 按时间粒度聚合销售额与订单量
 * GET /api/orders/stats
 * 参数：granularity(day|week|month), category, startDate, endDate
 * 返回：{ granularity, items: [{ period, sales, orders }] }
 */
export function getOrdersStats(params) {
  return request.get('/orders/stats', { params })
}

/**
 * A7 趋势对比（环比 previous / 同比 previousYear）
 * GET /api/orders/trend
 * 参数：granularity, compareType, category, startDate, endDate
 * 返回：{ currentPeriod, comparePeriod, items: [{ period, currentSales, currentOrders,
 *        prevSales, prevOrders, salesGrowthRate, ordersGrowthRate }] }
 */
export function getOrdersTrend(params) {
  return request.get('/orders/trend', { params })
}

/**
 * A8 客单价区间分布
 * GET /api/orders/price-distribution
 * 参数：category, startDate, endDate
 * 返回：{ totalOrders, totalSales, intervals: [{ range, orderCount, sales, orderRatio }] }
 */
export function getPriceDistribution(params) {
  return request.get('/orders/price-distribution', { params })
}

/**
 * A9 品类贡献分析（帕累托：销售额 + 累计占比）
 * GET /api/orders/category-contribution
 * 参数：startDate, endDate
 * 返回：{ totalSales, items: [{ rank, category, sales, salesRatio, cumulativeRatio }] }
 */
export function getCategoryContribution(params) {
  return request.get('/orders/category-contribution', { params })
}

/**
 * A10 下单时段分析（0-23 小时，固定 24 个元素）
 * GET /api/orders/hourly
 * 参数：category, startDate, endDate
 * 返回：{ hours: [{ hour, orderCount, sales }] }
 */
export function getHourlyStats(params) {
  return request.get('/orders/hourly', { params })
}

/**
 * A11 用户分析（新老用户 / 支付方式 / 复购率）
 * GET /api/orders/user-analysis
 * 参数：category, startDate, endDate
 * 返回：{ totalUsers, newUserCount, oldUserCount, newUserSales, oldUserSales,
 *        newUserAvgOrderValue, oldUserAvgOrderValue, newUserRate, repurchaseRate,
 *        payTypeRates: [{ payType, orderCount, rate }] }
 */
export function getUserAnalysis(params) {
  return request.get('/orders/user-analysis', { params })
}

/**
 * A15 促销活动效果（活动期 vs 非活动期）
 * GET /api/orders/campaign-effect
 * 参数：startDate(必填), endDate(必填), category
 * 返回：{ activityPeriod, nonActivityPeriod, activity, nonActivity,
 *        salesGrowthRate, ordersGrowthRate, promotionPullRate }
 */
export function getCampaignEffect(params) {
  return request.get('/orders/campaign-effect', { params })
}

/**
 * A16 RFM 用户分层
 * GET /api/users/rfm
 * 参数：startDate, endDate, category（默认窗口近 90 天）
 * 返回：{ windowStart, windowEnd, thresholds, totalUsers, totalSales,
 *        segments: [{ segment, userCount, sales, salesRatio, avgOrderValue, avgFrequency }] }
 */
export function getRfmAnalysis(params) {
  return request.get('/users/rfm', { params })
}

/**
 * A17 地域销售分布
 * GET /api/orders/region
 * 参数：startDate, endDate, category
 * 返回：{ items: [{ rank, region, sales, orderCount, salesRatio }] }
 */
export function getRegionSales(params) {
  return request.get('/orders/region', { params })
}
