import request from '@/utils/request'

/**
 * A2 订单分页查询
 * GET /api/orders
 * 参数：page(从1开始), size(最大100), category, keyword, payType, userType,
 *      promoFlag(0/1), startDate, endDate, sort(如 totalAmount,desc)
 * 返回：{ total, page, size, records: Order[] }
 */
export function getOrderPage(params) {
  return request.get('/orders', { params })
}

/**
 * A6 订单详情（明细下钻）
 * GET /api/orders/{id}
 * 订单号不存在时后端返回 404（拦截器统一提示）
 */
export function getOrderDetail(orderId) {
  return request.get(`/orders/${encodeURIComponent(orderId)}`)
}
