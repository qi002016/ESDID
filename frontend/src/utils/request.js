import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 过滤空参数：'', null, undefined 一律不发送，避免后端把空串当作筛选条件
 */
export function cleanParams(params = {}) {
  const result = {}
  Object.keys(params).forEach((key) => {
    const value = params[key]
    if (value !== '' && value !== null && value !== undefined) result[key] = value
  })
  return result
}

// axios 实例：baseURL 来自环境变量（开发环境 / 生产 nginx 都走 /api 前缀）
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60000
})

// HTTP 状态码 -> 友好提示（接口文档 2.3 节错误码）
const STATUS_MESSAGE = {
  400: '请求参数错误',
  404: '请求的资源不存在',
  409: '状态冲突，请稍后重试',
  500: '服务器内部错误'
}

/**
 * 错误提示去重：
 * 看板首屏会并发十几个请求，后端一旦不可用会同时失败，
 * 逐条弹提示会造成十几条相同 toast 刷屏。这里做 3 秒窗口内的同文案合并。
 */
const MESSAGE_DEDUPE_MS = 3000
let lastMessage = ''
let lastMessageAt = 0

function notifyError(message) {
  const text = message || '请求失败'
  const now = Date.now()
  if (text === lastMessage && now - lastMessageAt < MESSAGE_DEDUPE_MS) return
  lastMessage = text
  lastMessageAt = now
  ElMessage.error(text)
}

/**
 * 读取 Blob 响应体中的错误信息：
 * 导出接口返回二进制流，若出错后端会返回 JSON，需要转文本解析
 */
async function readBlobError(blob) {
  try {
    const text = await blob.text()
    const json = JSON.parse(text)
    return json.message || ''
  } catch (e) {
    return ''
  }
}

// ---------------- 响应拦截器 ----------------
request.interceptors.response.use(
  async (response) => {
    // 1) 文件下载（A12 CSV 导出）：把整个 response 交给调用方，从中取 blob 与文件名
    if (response.config.responseType === 'blob') {
      const blob = response.data
      const type = blob?.type || ''
      // 后端出错时会返回 JSON 类型的 blob，这里识别并抛出统一错误
      if (type.includes('application/json')) {
        const msg = await readBlobError(blob)
        notifyError(msg || '导出失败')
        return Promise.reject(new Error(msg || '导出失败'))
      }
      return response
    }

    // 2) 统一响应结构 { code, message, data }
    const { code, message, data } = response.data || {}
    if (code === 0) return data

    // 业务失败：统一弹提示，业务组件无需再写 if (code !== 0)
    notifyError(message || '请求失败')
    return Promise.reject(new Error(message || '请求失败'))
  },
  (error) => {
    // HTTP 层错误（400/404/409/500...）：统一提示
    const status = error.response?.status
    const msg =
      error.response?.data?.message ||
      STATUS_MESSAGE[status] ||
      (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络异常，请检查后端服务是否启动')
    notifyError(msg)
    return Promise.reject(error)
  }
)

export default request
