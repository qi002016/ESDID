<template>
  <!-- A18 CSV 批量导入 + A19 任务状态轮询 -->
  <el-dialog
    v-model="visible"
    title="订单 CSV 批量导入"
    width="760px"
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <div class="import">
      <!-- 1. 选择文件 + 导入模式 -->
      <el-upload
        ref="uploadRef"
        drag
        accept=".csv"
        :auto-upload="false"
        :limit="1"
        :on-change="handleFileChange"
        :on-remove="handleFileRemove"
        :on-exceed="handleExceed"
        :disabled="isProcessing"
      >
        <div class="import__upload-text">
          将 CSV 文件拖到此处，或<em>点击选择文件</em>
        </div>
        <template #tip>
          <div class="import__tip">
            列顺序须与接口文档一致：订单号,商品名,品类,单价,数量,总金额,下单时间,用户ID,用户类型,支付方式
          </div>
        </template>
      </el-upload>

      <div class="import__mode">
        <span class="import__label">导入模式</span>
        <el-radio-group v-model="append" :disabled="isProcessing">
          <el-radio :value="0">覆盖导入（先清空再写入）</el-radio>
          <el-radio :value="1">追加导入</el-radio>
        </el-radio-group>
      </div>

      <el-alert
        v-if="append === 0"
        type="warning"
        :closable="false"
        show-icon
        title="覆盖导入会先清空现有订单数据，请谨慎操作"
        class="import__alert"
      />

      <!-- 2. 任务进度：PROCESSING 轮询中 -->
      <div v-if="taskId" class="import__progress">
        <div class="import__progress-head">
          <span>任务 ID：{{ taskId }}</span>
          <el-tag :type="statusTagType" size="small" effect="dark">{{ statusText }}</el-tag>
        </div>
        <el-progress
          :percentage="progressPercent"
          :status="progressStatus"
          :stroke-width="12"
          striped
          :striped-flow="isProcessing"
        />
        <div class="import__progress-tip">
          {{ isProcessing ? '任务处理中，每 2 秒自动刷新一次进度...' : '任务已结束' }}
        </div>
      </div>

      <!-- 3. 导入结果报告 -->
      <div v-if="report" class="import__report">
        <div class="import__stats">
          <div class="import__stat">
            <span>总行数</span><b>{{ formatNumber(report.totalRows) }}</b>
          </div>
          <div class="import__stat import__stat--success">
            <span>成功行数</span><b>{{ formatNumber(report.validRows) }}</b>
          </div>
          <div class="import__stat import__stat--danger">
            <span>失败行数</span><b>{{ formatNumber(report.errorRows) }}</b>
          </div>
          <div class="import__stat">
            <span>耗时</span><b>{{ durationText }}</b>
          </div>
        </div>

        <div class="import__time">
          开始：{{ report.startTime || '-' }} · 结束：{{ report.endTime || '-' }}
        </div>

        <!-- 错误行明细：逐行校验报告 -->
        <div v-if="errorList.length" class="import__errors">
          <div class="import__errors-title">错误明细（{{ errorList.length }} 条）</div>
          <el-table :data="errorList" size="small" border max-height="220">
            <el-table-column prop="row" label="行号" width="90" align="center" />
            <el-table-column prop="field" label="字段" width="140" show-overflow-tooltip />
            <el-table-column prop="message" label="错误原因" min-width="240" show-overflow-tooltip />
          </el-table>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ isProcessing ? '后台继续' : '关闭' }}</el-button>
      <el-button
        type="primary"
        :loading="uploading"
        :disabled="!file || isProcessing"
        @click="handleImport"
      >
        开始导入
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getImportTaskStatus, importOrdersCsv } from '@/api'
import { formatNumber } from '@/utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'finished'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

/** 任务终态：轮询到这三个状态即停止（接口文档 5.19） */
const TERMINAL_STATUS = ['SUCCESS', 'PARTIAL', 'FAILED']
const POLL_INTERVAL = 2000

const uploadRef = ref(null)
const file = ref(null)
const append = ref(1)
const uploading = ref(false)
const taskId = ref('')
const taskStatus = ref('')
const report = ref(null)
const pollCount = ref(0)

// 轮询定时器：组件销毁 / 弹窗关闭时必须清理，避免内存泄漏与无效请求
let pollTimer = null

const isProcessing = computed(() => taskStatus.value === 'PROCESSING')
const errorList = computed(() => (Array.isArray(report.value?.errors) ? report.value.errors : []))

const statusText = computed(
  () =>
    ({
      PROCESSING: '处理中',
      SUCCESS: '导入成功',
      PARTIAL: '部分成功',
      FAILED: '导入失败'
    })[taskStatus.value] || '等待中'
)

const statusTagType = computed(
  () =>
    ({
      PROCESSING: 'primary',
      SUCCESS: 'success',
      PARTIAL: 'warning',
      FAILED: 'danger'
    })[taskStatus.value] || 'info'
)

/** 进度条：处理中按轮询次数估算（后端未提供服务端进度时避免假死感） */
const progressPercent = computed(() => {
  if (isProcessing.value) return Math.min(90, 10 + pollCount.value * 8)
  if (taskStatus.value === 'SUCCESS') return 100
  if (taskStatus.value === 'PARTIAL') return 100
  return taskStatus.value === 'FAILED' ? 100 : 0
})

const progressStatus = computed(() =>
  taskStatus.value === 'SUCCESS' ? 'success' : taskStatus.value === 'FAILED' ? 'exception' : undefined
)

/** 耗时：endTime - startTime（秒） */
const durationText = computed(() => {
  const { startTime, endTime } = report.value || {}
  if (!startTime || !endTime) return '-'
  const start = new Date(String(startTime).replace(/-/g, '/')).getTime()
  const end = new Date(String(endTime).replace(/-/g, '/')).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) return '-'
  const seconds = Math.max(0, Math.round((end - start) / 1000))
  return `${seconds} 秒`
})

/** 选择文件 */
function handleFileChange(uploadFile) {
  const raw = uploadFile?.raw
  if (!raw) return
  if (!/\.csv$/i.test(raw.name)) {
    ElMessage.warning('请选择 .csv 格式的文件')
    uploadRef.value?.clearFiles()
    file.value = null
    return
  }
  file.value = raw
}

function handleFileRemove() {
  file.value = null
}

/** 限制单个文件 */
function handleExceed(files) {
  uploadRef.value?.clearFiles()
  const next = files[0]
  uploadRef.value?.handleStart(next)
  file.value = next
}

/** 清理轮询定时器 */
function clearPollTimer() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** A19 轮询任务状态，直到终态 */
function startPolling(id) {
  clearPollTimer()
  pollCount.value = 0
  pollTimer = setInterval(async () => {
    try {
      pollCount.value += 1
      const data = await getImportTaskStatus(id)
      taskStatus.value = data?.status || 'PROCESSING'
      report.value = data || null
      if (TERMINAL_STATUS.includes(taskStatus.value)) {
        clearPollTimer()
        if (taskStatus.value === 'SUCCESS') ElMessage.success('导入完成')
        else if (taskStatus.value === 'PARTIAL') ElMessage.warning('导入完成，存在失败行')
        else ElMessage.error('导入失败，请查看错误明细')
        // 通知父组件刷新看板数据
        emit('finished', data)
      }
    } catch (e) {
      // 任务不存在（404）等情况：停止轮询，避免无效请求持续刷屏
      clearPollTimer()
      taskStatus.value = 'FAILED'
    }
  }, POLL_INTERVAL)
}

/** A18 上传 CSV -> 拿到 taskId -> 开始轮询 A19 */
async function handleImport() {
  if (!file.value) {
    ElMessage.warning('请先选择 CSV 文件')
    return
  }
  uploading.value = true
  report.value = null
  taskStatus.value = ''
  try {
    const data = await importOrdersCsv(file.value, append.value)
    taskId.value = data?.taskId || ''
    taskStatus.value = data?.status || 'PROCESSING'
    if (!taskId.value) {
      ElMessage.error('未获取到导入任务 ID')
      return
    }
    ElMessage.info('文件已上传，正在处理...')
    startPolling(taskId.value)
  } catch (e) {
    // 拦截器已统一提示（含 409 重复导入冲突）
    taskStatus.value = ''
  } finally {
    uploading.value = false
  }
}

/** 每次打开弹窗重置状态（上一次的结果不残留） */
function resetState() {
  clearPollTimer()
  file.value = null
  append.value = 1
  uploading.value = false
  taskId.value = ''
  taskStatus.value = ''
  report.value = null
  pollCount.value = 0
  uploadRef.value?.clearFiles()
}

function handleClosed() {
  // 关闭弹窗立即停止轮询（后台任务本身不受影响）
  clearPollTimer()
  resetState()
}

watch(
  () => props.modelValue,
  (show) => {
    if (show) resetState()
  }
)

// 组件销毁：清除定时器，防止内存泄漏
onUnmounted(clearPollTimer)
</script>

<style lang="scss" scoped>
.import {
  &__upload-text {
    font-size: 14px;
    color: var(--t-sub);

    em {
      color: var(--c-primary);
      font-style: normal;
    }
  }

  &__tip {
    font-size: 12px;
    color: var(--t-muted);
    margin-top: 6px;
  }

  &__mode {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 16px;
  }

  &__label {
    font-size: 13px;
    color: var(--t-muted);
  }

  &__alert {
    margin-top: 12px;
  }

  &__progress {
    margin-top: 16px;
  }

  &__progress-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    color: var(--t-sub);
    margin-bottom: 8px;
  }

  &__progress-tip {
    font-size: 12px;
    color: var(--t-muted);
    margin-top: 6px;
  }

  &__report {
    margin-top: 16px;
  }

  &__stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
    gap: 10px;
  }

  &__stat {
    background: var(--bg-soft);
    border-radius: 6px;
    padding: 10px 12px;
    display: flex;
    flex-direction: column;
    gap: 2px;

    span {
      font-size: 12px;
      color: var(--t-muted);
    }

    b {
      font-size: 18px;
      color: var(--t-main);
    }

    &--success b {
      color: var(--c-success);
    }

    &--danger b {
      color: var(--c-danger);
    }
  }

  &__time {
    margin-top: 10px;
    font-size: 12px;
    color: var(--t-muted);
  }

  &__errors {
    margin-top: 12px;
  }

  &__errors-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--t-main);
    margin-bottom: 8px;
  }
}
</style>
