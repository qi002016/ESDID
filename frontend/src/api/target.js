import request from '@/utils/request'

/**
 * A13 销售目标列表
 * GET /api/targets
 * 参数：periodType(month), periodValue(如 2026-09), category
 * 返回：{ periodType, periodValue, items: [{ id, category, targetAmount }] }
 */
export function getTargets(params) {
  return request.get('/targets', { params })
}

/**
 * A14 目标达成率
 * GET /api/orders/target-progress
 * 参数：periodType, periodValue, category
 * 返回：{ totalTarget, achievedSales, achievementRate, remainingDays, dailyNeeded,
 *        items: [{ category, targetAmount, achieved, achievementRate }] }
 */
export function getTargetProgress(params) {
  return request.get('/orders/target-progress', { params })
}
