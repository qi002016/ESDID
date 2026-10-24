/**
 * assistant.js — AI 智能问答（Text-to-SQL Agent）API 封装
 * =======================================================
 * 为什么不走 axios？
 *   agent 服务返回的是 text/event-stream（SSE），需要逐帧读取；
 *   而 utils/request.js 的 axios 实例会统一解包 { code, message, data }，
 *   还会对非 2xx 做全局弹窗——都不适合流式场景。
 *   所以这里用浏览器原生 fetch + ReadableStream 逐帧解析，事件通过 onEvent 回调吐出。
 *
 * 网络路径：
 *   开发环境  /agent/chat  → Vite proxy  → http://localhost:8000/chat
 *   生产环境  /agent/chat  → nginx proxy  → http://127.0.0.1:8000/chat
 *   全程同源，无跨域；前端无需知道 agent 的真实地址（网关解耦）。
 */

/**
 * 发送一条消息并流式接收 SSE 事件
 * @param {object} options
 * @param {string} options.sessionId  会话 ID（指代消解依赖，存 localStorage）
 * @param {string} options.message    用户问题
 * @param {Function} options.onEvent  收到事件回调：{ type: thinking|sql|answer|chart|done, content }
 * @param {AbortSignal} [options.signal] 取消信号
 * @returns {Promise<void>} 流结束或出错时 resolve/reject
 */
export async function chatStream({ sessionId, message, onEvent, signal }) {
  const resp = await fetch('/agent/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ session_id: sessionId, message }),
    signal
  })

  if (!resp.ok) {
    let msg = `请求失败（${resp.status}）`
    try {
      const data = await resp.json()
      if (data?.msg) msg = data.msg
    } catch (e) {
      /* 非 JSON 错误体，保留默认文案 */
    }
    throw new Error(msg)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 帧以空行分隔，末尾不完整帧留在 buffer 等下一段
    const frames = buffer.split('\n\n')
    buffer = frames.pop() || ''
    for (const frame of frames) {
      for (const line of frame.split('\n')) {
        if (!line.startsWith('data: ')) continue
        try {
          onEvent(JSON.parse(line.slice(6)))
        } catch (e) {
          /* 单帧解析失败跳过，不中断整条流 */
        }
      }
    }
  }
}
