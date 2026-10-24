import dayjs from 'dayjs'

/** 数值安全转换：null/undefined/'' 一律返回 null，方便调用方判断边界 */
export function toNumber(value) {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isNaN(num) ? null : num
}

/** 金额格式化：保留 2 位小数 + 千分位；null/非法值返回 '0.00' */
export function formatMoney(value) {
  const num = toNumber(value)
  if (num === null) return '0.00'
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** 金额 + ￥符号 */
export function formatCurrency(value) {
  return `￥${formatMoney(value)}`
}

/** 数量格式化：千分位整数 */
export function formatNumber(value) {
  const num = toNumber(value)
  if (num === null) return '0'
  return num.toLocaleString('zh-CN')
}

/**
 * 比率格式化：后端返回 0~1 小数（如 0.39 -> 39.00%）
 * 增长率为 null 时返回 '--'（文档明确：数据不足时 growthRate 为 null）
 */
export function formatPercent(value, digits = 2) {
  const num = toNumber(value)
  if (num === null) return '--'
  return `${(num * 100).toFixed(digits)}%`
}

/** 大额金额紧凑显示：12.30万 / 1.20亿，用于图表坐标轴 */
export function formatCompact(value) {
  const num = toNumber(value)
  if (num === null) return '0'
  const abs = Math.abs(num)
  if (abs >= 100000000) return `${(num / 100000000).toFixed(2)}亿`
  if (abs >= 10000) return `${(num / 10000).toFixed(2)}万`
  return formatMoney(num)
}

/** 日期格式化：接口参数严格 yyyy-MM-dd */
export function formatDate(date, pattern = 'YYYY-MM-DD') {
  return date ? dayjs(date).format(pattern) : ''
}

/** 快捷日期范围：近 N 天（含今天），返回 [startDate, endDate]，格式 yyyy-MM-dd */
export function recentRange(days) {
  const end = dayjs().format('YYYY-MM-DD')
  const start = dayjs().subtract(days - 1, 'day').format('YYYY-MM-DD')
  return [start, end]
}

/**
 * 计算「上一等长周期」：用于 KPI 环比对比
 * 例：2026-09-01 ~ 2026-09-30（30 天） -> 2026-08-02 ~ 2026-08-31
 */
export function previousRange(startDate, endDate) {
  if (!startDate || !endDate) return ['', '']
  const days = dayjs(endDate).diff(dayjs(startDate), 'day') + 1
  const prevEnd = dayjs(startDate).subtract(1, 'day')
  const prevStart = prevEnd.subtract(days - 1, 'day')
  return [prevStart.format('YYYY-MM-DD'), prevEnd.format('YYYY-MM-DD')]
}

/**
 * 增长率：value 为 0~1 小数；分母为 0 / 空 / null 时返回 null（前端展示为"无对比数据"）
 */
export function growthRate(current, previous, invert = false) {
  const cur = toNumber(current)
  const prev = toNumber(previous)
  if (cur === null || prev === null || prev === 0) return null
  const rate = (cur - prev) / Math.abs(prev)
  return invert ? Number((-rate).toFixed(4)) : Number(rate.toFixed(4))
}

/** 当前月份：yyyy-MM（用于 A13/A14 的 periodValue） */
export function currentMonth() {
  return dayjs().format('YYYY-MM')
}

/** 枚举字典：值与接口文档保持一致 */
export const PAY_TYPE_OPTIONS = [
  { label: '支付宝', value: 'alipay' },
  { label: '微信', value: 'wechat' },
  { label: '银行卡', value: 'card' }
]

export const USER_TYPE_OPTIONS = [
  { label: '新用户', value: 'new' },
  { label: '老用户', value: 'old' }
]

export const PROMO_FLAG_OPTIONS = [
  { label: '促销单', value: 1 },
  { label: '非促销单', value: 0 }
]

/** 枚举翻译（表格展示用） */
export function translatePayType(value) {
  return PAY_TYPE_OPTIONS.find((item) => item.value === value)?.label || value || '-'
}

export function translateUserType(value) {
  return USER_TYPE_OPTIONS.find((item) => item.value === value)?.label || value || '-'
}

/** 数组安全取值：后端可能返回 null */
export function safeArray(value) {
  return Array.isArray(value) ? value : []
}
