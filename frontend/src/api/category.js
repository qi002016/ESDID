import request from '@/utils/request'
import { cleanParams } from '@/utils/request'

/**
 * A1 品类列表
 * GET /api/categories
 * 返回：[{ category, productCount }]
 */
export function getCategories() {
  return request.get('/categories')
}

/**
 * 带全局筛选参数的请求包装：
 * 统一过滤空值，避免把空串传给后端造成 400
 */
export function withParams(params) {
  return cleanParams(params)
}
