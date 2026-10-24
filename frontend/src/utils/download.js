/**
 * CSV 下载工具（A12 导出接口返回 Blob）
 */

/** 用 Blob 触发浏览器下载 */
export function downloadBlob(blob, filename = 'export.csv') {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * 从 Content-Disposition 解析文件名
 * 后端形如：attachment; filename="orders_20260901_20260915.csv"
 */
export function resolveFilename(response, fallback = 'orders.csv') {
  const disposition = response?.headers?.['content-disposition'] || ''
  const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i)
  if (!match) return fallback
  try {
    return decodeURIComponent(match[1])
  } catch (e) {
    return match[1]
  }
}

/** 兜底文件名：orders_20260901_20260915.csv */
export function buildCsvFilename(startDate, endDate) {
  const start = (startDate || '').replace(/-/g, '')
  const end = (endDate || '').replace(/-/g, '')
  return start && end ? `orders_${start}_${end}.csv` : 'orders.csv'
}
