import request from '@/utils/request'

/**
 * A12 订单明细 CSV 导出（流式，Blob 下载）
 * GET /api/orders/export
 * 参数：category, keyword, payType, userType, startDate, endDate
 * 注意：responseType='blob'，拦截器会把整个 response 返回（含响应头文件名）
 */
export function exportOrdersCsv(params) {
  return request.get('/orders/export', { params, responseType: 'blob' })
}

/**
 * A18 订单 CSV 批量导入（异步任务）
 * POST /api/orders/import  Content-Type: multipart/form-data
 * 参数：file(File, 必填), append(0 覆盖导入 / 1 追加导入)
 * 返回：{ taskId, status }
 */
export function importOrdersCsv(file, append = 0) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('append', append)
  return request.post('/orders/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * A19 导入任务状态查询（前端轮询至终态）
 * GET /api/orders/import/tasks/{taskId}
 * 返回：{ taskId, status: PROCESSING|SUCCESS|PARTIAL|FAILED, totalRows, validRows,
 *        errorRows, startTime, endTime, errors: [{ row, field, message }] }
 */
export function getImportTaskStatus(taskId) {
  return request.get(`/orders/import/tasks/${encodeURIComponent(taskId)}`)
}
