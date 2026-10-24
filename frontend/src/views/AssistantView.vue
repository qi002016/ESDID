<template>
  <div class="assistant">
    <!-- 对话卡片：沿用业务系统设计令牌（--bg-card / --sh-card / --r-card） -->
    <div class="assistant__card">
      <!-- 头部 -->
      <div class="assistant__head">
        <div>
          <h3 class="assistant__title">AI 智能问答</h3>
          <p class="assistant__sub">用自然语言问数据 · 自动生成 SQL 与图表 · 支持多轮追问</p>
        </div>
        <div class="assistant__head-actions">
          <button type="button" class="assistant__clear" @click="openHistory">🕘 历史会话</button>
          <button
            type="button"
            class="assistant__clear"
            :disabled="busy || !messages.length"
            @click="clearChat"
          >清空会话</button>
        </div>
      </div>

      <!-- 示例问题 -->
      <div class="assistant__examples">
        <span
          v-for="q in examples"
          :key="q"
          :class="{ 'is-disabled': busy }"
          @click="ask(q)"
        >{{ q }}</span>
      </div>

      <!-- 消息流 -->
      <div ref="scrollBox" class="assistant__messages">
        <template v-if="messages.length === 0 && !thinking">
          <div class="assistant__empty">
            <p>输入问题或点击上方示例，即可让 AI 帮你分析销售数据。</p>
          </div>
        </template>

        <template v-for="(msg, i) in messages" :key="i">
          <!-- 用户消息 -->
          <div v-if="msg.role === 'user'" class="msg msg--user">{{ msg.text }}</div>

          <!-- 助手消息 -->
          <div v-else class="msg msg--bot">
            <div v-if="!msg.error" class="msg__text">{{ msg.text }}</div>
            <template v-if="msg.error">
              <div class="msg__error">{{ msg.text }}</div>
            </template>
            <div v-for="chart in msg.charts" :key="chart.title || chartIndex(msg, chart)" class="msg__chart">
              <div v-if="chart.title" class="msg__chart-title">{{ chart.title }}</div>
              <div :ref="setChartRef(chart)" class="msg__chart-box"></div>
            </div>
          </div>
        </template>

        <!-- 进行中状态行 -->
        <div v-if="thinking" class="msg msg--thinking">⏳ {{ thinking }}</div>
      </div>

      <!-- 调试：最近一次 SQL（默认隐藏） -->
      <div class="assistant__debug">
        <button type="button" class="assistant__debug-btn" @click="showSQL = !showSQL">
          🔍 调试：查看 SQL
        </button>
        <pre v-if="showSQL" class="assistant__debug-sql">{{ lastSQL || '（暂无查询记录）' }}</pre>
      </div>

      <!-- 输入栏 -->
      <div class="assistant__inputbar">
        <input
          v-model="draft"
          type="text"
          placeholder="例如：上个月整体销售额和订单数是多少？"
          :disabled="busy"
          @keydown.enter="send"
        />
        <button type="button" :disabled="busy || !draft.trim()" @click="send">发送</button>
      </div>
    </div>

    <!-- 历史会话侧栏：列表 + 回放（继续该会话 = 切换 sessionId 后直接提问） -->
    <el-drawer
      v-model="historyVisible"
      title="历史会话"
      size="340px"
      :append-to-body="true"
    >
      <div v-if="loadingSessions" class="assistant__history-loading">加载中…</div>
      <p v-else-if="!sessions.length" class="assistant__history-empty">
        暂无历史会话。聊过的问题会自动保存到这里。
      </p>
      <ul v-else class="assistant__history-list">
        <li
          v-for="s in sessions"
          :key="s.session_id"
          class="assistant__history-item"
          :class="{ 'is-active': s.session_id === sessionId.value }"
          @click="loadSession(s)"
        >
          <b>{{ s.title }}</b>
          <span>{{ s.message_count }} 条消息 · {{ formatTime(s.updated_at) }}</span>
        </li>
      </ul>
    </el-drawer>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import echarts from '@/components/charts/echarts'
import { CHART_THEME_NAME, registerChartTheme } from '@/components/charts/chartTheme'
import { chatStream } from '@/api/assistant'

// 全局图表主题：与看板其他图表完全一致的视觉
registerChartTheme()

// ---------- 会话标识：与 agent 后端记忆一一对应 ----------
// 同一浏览器里旧独立页面与本页共用同一 key → 多轮指代（"那手机品类呢"）无缝延续
// ref：切换历史会话时更新，后续提问即"继续该会话"
const SESSION_KEY = 'text2sql_session_id'
const sessionId = ref(
  localStorage.getItem(SESSION_KEY) ||
    'sess_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
)
function persistSessionId() {
  localStorage.setItem(SESSION_KEY, sessionId.value)
}
persistSessionId()

const draft = ref('')
const busy = ref(false)
const thinking = ref('')
const lastSQL = ref('')
const showSQL = ref(false)
const messages = ref([])

// ---------- 历史会话（PostgreSQL 归档） ----------
const historyVisible = ref(false)
const sessions = ref([])
const loadingSessions = ref(false)

/** 打开历史侧栏并拉取会话列表 */
async function openHistory() {
  historyVisible.value = true
  if (sessions.value.length) return
  loadingSessions.value = true
  try {
    const resp = await fetch('/agent/sessions')
    const data = await resp.json()
    sessions.value = data.sessions || []
  } catch (e) {
    sessions.value = []
  } finally {
    loadingSessions.value = false
  }
}

/** 回放一个历史会话：拉取消息渲染到主消息流，并切换 sessionId（继续提问即续聊） */
async function loadSession(s) {
  try {
    const resp = await fetch(`/agent/sessions/${s.session_id}/messages`)
    const data = await resp.json()
    const list = data.messages || []
    // 历史消息 → 当前视图的消息模型（user / bot + charts）
    messages.value = list.map((m) =>
      m.role === 'user'
        ? { role: 'user', text: m.content }
        : { role: 'bot', text: m.content, charts: m.charts || [], error: false }
    )
    sessionId.value = s.session_id
    persistSessionId()
    historyVisible.value = false
    thinking.value = ''
    lastSQL.value = ''
    scrollToBottom()
  } catch (e) {
    // 历史接口不可用时不打断使用
  }
}

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const examples = [
  '上个月整体销售额和订单数是多少',
  '各品类销售额占比',
  '最近7天手机品类销售趋势',
  '2026年12月销售额环比变化多少',
  '12月各品类目标达成率'
]

const scrollBox = ref(null)
const chartEls = new Map() // chart 对象 -> DOM 容器
const chartInstances = [] // 所有 echarts 实例，卸载时统一 dispose

function scrollToBottom() {
  nextTick(() => {
    if (scrollBox.value) scrollBox.value.scrollTop = scrollBox.value.scrollHeight
  })
}

/** 每条消息渲染后，为其中的 chart 容器初始化 ECharts（幂等：已初始化的跳过） */
async function renderCharts() {
  await nextTick()
  chartEls.forEach((el, chart) => {
    if (!el || el.dataset.charted) return
    const inst = echarts.init(el, CHART_THEME_NAME)
    inst.setOption(chart.option || {}, true)
    el.dataset.charted = '1'
    chartInstances.push(inst)
  })
  scrollToBottom()
}

function setChartRef(chart) {
  return (el) => {
    if (el) chartEls.set(chart, el)
  }
}
function chartIndex(msg, chart) {
  return msg.charts.indexOf(chart)
}

// 消息流变化（新消息/新图表）→ 渲染图表并滚动到底
watch(messages, renderCharts, { deep: true })

async function send() {
  const text = draft.value.trim()
  if (!text || busy.value) return
  draft.value = ''
  busy.value = true
  showSQL.value = false
  thinking.value = ''
  lastSQL.value = ''
  messages.value.push({ role: 'user', text })

  // 预置助手消息位：answer 事件填 text，chart 事件追加图表
  const bot = { role: 'bot', text: '', charts: [], error: false }
  messages.value.push(bot)

  try {
    await chatStream({
      sessionId: sessionId.value,
      message: text,
      onEvent: (evt) => {
        switch (evt.type) {
          case 'thinking':
            thinking.value = evt.content
            break
          case 'sql':
            lastSQL.value = evt.content
            break
          case 'answer':
            bot.text = evt.content
            break
          case 'chart':
            bot.charts.push(evt.content)
            break
        }
      }
    })
  } catch (err) {
    bot.text = err.message || '请求失败，请确认 Agent 服务已启动'
    bot.error = true
  } finally {
    thinking.value = ''
    busy.value = false
  }
}

function ask(text) {
  draft.value = text
  send()
}

function clearChat() {
  if (busy.value) return
  messages.value = []
  lastSQL.value = ''
  thinking.value = ''
}

onBeforeUnmount(() => {
  chartInstances.forEach((inst) => inst.dispose())
  chartInstances.length = 0
  chartEls.clear()
})
</script>

<style lang="scss" scoped>
.assistant {
  display: flex;
  height: 100%;
  min-height: 0;

  &__card {
    display: flex;
    flex-direction: column;
    width: 100%;
    min-height: 0;
    background: var(--bg-card);
    border: 1px solid var(--bd-light);
    border-radius: var(--r-card);
    box-shadow: var(--sh-card);
    overflow: hidden;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 16px 20px 0;
    flex-shrink: 0;
  }

  &__head-actions {
    display: flex;
    gap: 8px;
    flex-shrink: 0;
  }

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: var(--t-main);
  }

  &__sub {
    margin-top: 2px;
    font-size: 12px;
    color: var(--t-muted);
  }

  &__clear {
    padding: 5px 14px;
    border: 1px solid var(--bd-base);
    border-radius: var(--r-control);
    background: var(--bg-card);
    color: var(--t-sub);
    font-size: 12px;
    font-family: inherit;
    cursor: pointer;
    transition: all 0.16s ease;

    &:hover:not(:disabled) {
      color: var(--c-danger);
      border-color: var(--c-danger);
    }

    &:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  }

  &__examples {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    padding: 12px 20px 0;
    flex-shrink: 0;

    span {
      font-size: 12px;
      color: var(--c-primary);
      background: var(--c-primary-50);
      padding: 4px 12px;
      border-radius: 999px;
      cursor: pointer;
      transition: background 0.16s ease;

      &:hover:not(.is-disabled) {
        background: var(--c-primary-100);
      }

      &.is-disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }
    }
  }

  &__messages {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 16px 20px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__empty {
    margin: auto;
    text-align: center;
    color: var(--t-muted);
    font-size: 13px;
  }

  &__debug {
    padding: 0 20px 8px;
    flex-shrink: 0;

    &-btn {
      font-size: 12px;
      color: var(--t-muted);
      background: var(--bg-soft);
      border: 1px solid var(--bd-base);
      border-radius: var(--r-control);
      padding: 3px 12px;
      cursor: pointer;
      font-family: inherit;

      &:hover {
        color: var(--t-sub);
        background: var(--bg-head);
      }
    }

    &-sql {
      margin: 8px 0 0;
      padding: 10px 14px;
      background: #262a30;
      color: #a7cfc2;
      font-family: Consolas, monospace;
      font-size: 12px;
      border-radius: var(--r-control);
      overflow-x: auto;
      white-space: pre-wrap;
      word-break: break-all;
      max-height: 160px;
      overflow-y: auto;
    }
  }

  /* ---- 历史会话侧栏 ---- */
  &__history-loading,
  &__history-empty {
    padding: 24px 0;
    text-align: center;
    font-size: 13px;
    color: var(--t-muted);
  }

  &__history-list {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  &__history-item {
    padding: 12px 14px;
    border: 1px solid var(--bd-light);
    border-radius: var(--r-control);
    margin-bottom: 8px;
    cursor: pointer;
    transition: all 0.16s ease;

    b {
      display: block;
      font-size: 13px;
      font-weight: 500;
      color: var(--t-main);
      margin-bottom: 4px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    span {
      font-size: 11.5px;
      color: var(--t-muted);
    }

    &:hover {
      border-color: var(--c-primary);
      background: var(--c-primary-50);
    }

    &.is-active {
      border-color: var(--c-primary);
      background: var(--c-primary-50);
      b {
        color: var(--c-primary);
      }
    }
  }

  &__inputbar {
    display: flex;
    gap: 10px;
    padding: 0 20px 16px;
    flex-shrink: 0;

    input {
      flex: 1;
      min-width: 0;
      padding: 10px 14px;
      border: 1px solid var(--bd-base);
      border-radius: var(--r-control);
      font-size: 13.5px;
      font-family: inherit;
      color: var(--t-main);
      outline: none;
      transition: border-color 0.16s ease;

      &:focus {
        border-color: var(--c-primary);
        box-shadow: 0 0 0 3px var(--c-primary-50);
      }

      &:disabled {
        background: var(--bg-soft);
      }
    }

    button {
      padding: 10px 24px;
      border: none;
      border-radius: var(--r-control);
      background: var(--c-primary);
      color: #fff;
      font-size: 13.5px;
      font-family: inherit;
      cursor: pointer;
      transition: background 0.16s ease;

      &:hover:not(:disabled) {
        background: var(--c-primary-hover);
      }

      &:disabled {
        background: var(--t-muted);
        cursor: not-allowed;
      }
    }
  }
}

/* ---- 消息气泡 ---- */
.msg {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.7;
  font-size: 13.5px;
  white-space: pre-wrap;
  word-break: break-word;

  &--user {
    align-self: flex-end;
    background: var(--c-primary);
    color: #fff;
    border-bottom-right-radius: 2px;
  }

  &--bot {
    align-self: flex-start;
    background: var(--bg-soft);
    border-bottom-left-radius: 2px;
    color: var(--t-main);
    box-shadow: inset 0 0 0 1px var(--bd-light);
  }

  &--thinking {
    align-self: flex-start;
    color: var(--t-muted);
    font-size: 12.5px;
    font-style: italic;
  }

  &__error {
    margin-top: 6px;
    padding: 8px 10px;
    border-radius: 6px;
    background: var(--c-danger-50);
    color: var(--c-danger);
    font-size: 12.5px;
  }

  &__chart {
    margin-top: 12px;
    padding: 12px;
    background: var(--bg-card);
    border: 1px solid var(--bd-light);
    border-radius: 10px;

    &-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--t-main);
      margin-bottom: 8px;
    }

    &-box {
      width: 100%;
      height: 300px;
    }
  }
}
</style>
