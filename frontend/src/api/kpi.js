import request from '@/utils/request'

/**
 * 全局筛选透传说明：
 * 文档中本组接口仅声明 category / startDate / endDate，
 * 但筛选栏的「支付方式 / 用户类型 / 促销标记」属全局筛选，由 store.statParams 一并透传。
 * 后端未实现对应过滤时会忽略未知参数，不影响兼容性。
 */

/**
 * A3 KPI 汇总
 * GET /api/orders/kpi
 * 参数：category, startDate, endDate（+ payType, userType, promoFlag 全局筛选）
 * 返回：{ totalSales, totalOrders, avgOrderValue, hotProductCount,
 *        totalUsers, newUserRate, repurchaseRate }
 */
export function getOrdersKpi(params) {
  return request.get('/orders/kpi', { params })
}
