<template>
  <!-- A13 目标配置 + A14 达成率：仪表盘 | 核心指标 | 品类进度 -->
  <ChartCard title="销售目标达成率" :subtitle="subtitle" :loading="loading" :empty="empty">
    <!-- 外层包裹用于建立 container query 上下文：本卡片会与销售趋势 7:5 并排，
         需要按「卡片自身宽度」而非视口宽度决定内部栏数 -->
    <div class="target-box">
      <div class="target" :class="{ 'target--solo': !mergedItems.length }">
        <div ref="chartRef" class="chart chart--gauge"></div>

        <div class="target__info">
          <div class="target__row">
            <span>目标金额</span><b>￥{{ formatMoney(progress.totalTarget) }}</b>
          </div>
          <div class="target__row">
            <span>已达成</span><b class="is-primary">￥{{ formatMoney(progress.achievedSales) }}</b>
          </div>
          <div class="target__row">
            <span>剩余天数</span><b>{{ remainingDaysText }}</b>
          </div>
          <div class="target__row">
            <span>日均需达成</span>
            <!-- 这一行必须始终是具体金额：后端已按「剩余缺口 ÷ 剩余自然日」计算，
                 已达标时返回 0，也只渲染 ￥0.00，不能再用「无需追平」文字顶替指标 -->
            <span class="target__value">
              <b :class="overAchieved ? 'is-done' : 'is-warning'" :title="dailyNeededTitle">
                {{ dailyNeededText }}
              </b>
              <!-- 金额为主，状态仅作补充：不能再用「无需追平」整体替换掉指标值 -->
              <em v-if="overAchieved" class="target__note">已超额</em>
            </span>
          </div>
        </div>

        <!-- 品类目标进度：A13 目标额 + A14 达成额按品类合并；
             不设 max-height / overflow，全部品类自然展开，底部不再被截断 -->
        <div v-if="mergedItems.length" class="target__list">
          <p class="target__list-title">品类目标进度</p>
          <div v-for="item in mergedItems" :key="item.category" class="target__list-item">
            <div class="target__list-head">
              <span>{{ item.category === 'ALL' ? '全部品类' : item.category }}</span>
              <span>{{ formatPercent(item.achievementRate) }}</span>
            </div>
            <el-progress
              :percentage="Math.min(toPercent(item.achievementRate), 100)"
              :stroke-width="8"
              :show-text="false"
              :color="progressColor(item.achievementRate)"
            />
            <div class="target__list-foot">
              <span>￥{{ formatMoney(item.achieved) }}</span>
              <span>/ ￥{{ formatMoney(item.targetAmount) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </ChartCard>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import ChartCard from './ChartCard.vue'
import { COLORS, TEXT_COLOR, TEXT_SUB, withAlpha } from './chartTheme'
import { renderChart, useChart } from './useChart'
import { useDashboardStore } from '@/store/dashboard'
import { getTargetProgress, getTargets } from '@/api'
import { formatMoney, formatPercent, safeArray, toNumber } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')
const progress = ref({})
const mergedItems = ref([])

const { setOption, clear } = useChart(chartRef)

const remainingDaysText = computed(() => {
  const days = toNumber(progress.value?.remainingDays)
  return days === null ? '--' : `${days} 天`
})

/** 已达成 >= 目标：此时 dailyNeeded 被后端归零，展示上改说「无需追平」 */
const overAchieved = computed(() => {
  const target = toNumber(progress.value?.totalTarget)
  const achieved = toNumber(progress.value?.achievedSales)
  return target !== null && achieved !== null && target > 0 && achieved >= target
})

/** 超出目标的金额（已达成 - 目标），仅在 overAchieved 时有意义 */
const overAmount = computed(() => {
  const target = toNumber(progress.value?.totalTarget) ?? 0
  const achieved = toNumber(progress.value?.achievedSales) ?? 0
  return Math.max(achieved - target, 0)
})

/** 日均需达成金额：后端返回 null（周期已结束）时展示 --，绝不显示成 ￥0.00 */
const dailyNeededText = computed(() => {
  const value = toNumber(progress.value?.dailyNeeded)
  return value === null ? '--' : `￥${formatMoney(value)}`
})

/** 悬停说明：金额背后的口径，避免 ￥0.00 被误读成「没算出来」 */
const dailyNeededTitle = computed(() => {
  if (overAchieved.value) {
    return `目标已完成 ${formatPercent(progress.value?.achievementRate)}，已超出 ￥${formatMoney(
      overAmount.value
    )}`
  }
  const target = toNumber(progress.value?.totalTarget) ?? 0
  const achieved = toNumber(progress.value?.achievedSales) ?? 0
  return `剩余缺口 ￥${formatMoney(Math.max(target - achieved, 0))}，需在 ${remainingDaysText.value} 内完成`
})

/** 达成率 -> el-progress 需要的 0~100 */
function toPercent(rate) {
  const num = toNumber(rate)
  if (num === null) return 0
  return Number((num * 100).toFixed(1))
}

function progressColor(rate) {
  const percent = toPercent(rate)
  if (percent >= 100) return COLORS.success
  if (percent >= 70) return COLORS.primary
  if (percent >= 40) return COLORS.warning
  return COLORS.danger
}

/** 环形仪表盘：达成率进度环，超 100% 也能正常展示 */
function buildOption(rate) {
  const percent = Math.min(toPercent(rate), 100)
  const raw = toPercent(rate)
  return {
    series: [
      {
        type: 'gauge',
        startAngle: 220,
        endAngle: -40,
        min: 0,
        max: 100,
        radius: '92%',
        center: ['50%', '56%'],
        progress: {
          show: true,
          width: 16,
          roundCap: true,
          itemStyle: { color: progressColor(rate) }
        },
        axisLine: { lineStyle: { width: 16, color: [[1, withAlpha(COLORS.slate, 0.18)]] } },
        pointer: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        anchor: { show: false },
        title: {
          show: true,
          offsetCenter: [0, '28%'],
          fontSize: 12,
          color: TEXT_SUB
        },
        detail: {
          valueAnimation: true,
          offsetCenter: [0, '-2%'],
          fontSize: 22,
          fontWeight: 'bold',
          color: TEXT_COLOR,
          formatter: () => (raw === 0 && rate === null ? '--' : `${raw}%`)
        },
        data: [{ value: percent, name: '目标达成率' }]
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    // A13 目标额是业务配置（按周期 + 品类），不随支付方式等维度变化
    const targetParams = {
      periodType: 'month',
      periodValue: store.periodValue,
      ...(store.category ? { category: store.category } : {})
    }
    // A14 的「已达成」必须与目标额同口径：目标额是「周期 + 品类」的业务配置，
    // 若把支付方式 / 用户类型 / 促销标记这类渠道切片也应用到达成额，目标额却不变，
    // 就会出现「已达成 0 / 目标 100 万 / 日均需达成 12.5 万」这种无效对比。
    // 也不能直接展开 store.statParams——startDate/endDate 会把月度目标截断成日区间。
    const progressParams = { ...targetParams }
    // A14 计算达成率，A13 提供目标配置；两者并行请求
    const [progressData, targetData] = await Promise.all([
      getTargetProgress(progressParams),
      getTargets(targetParams)
    ])
    progress.value = progressData || {}
    subtitle.value = progressData
      ? `周期 ${progressData.periodValue || store.periodValue} · 目标 ￥${formatMoney(
          progressData.totalTarget
        )} · 口径：${store.category || '全店'}`
      : ''

    // 合并目标配置与达成情况：以 A14 明细为主，A13 补充目标额（A14 缺失时兜底）
    const targetMap = new Map(
      safeArray(targetData?.items).map((item) => [item.category, item.targetAmount])
    )
    mergedItems.value = safeArray(progressData?.items).map((item) => ({
      category: item.category,
      targetAmount: item.targetAmount ?? targetMap.get(item.category) ?? 0,
      achieved: item.achieved ?? 0,
      achievementRate: item.achievementRate
    }))

    if (!progressData || progressData.totalTarget === null || progressData.totalTarget === undefined) {
      empty.value = true
      clear()
      return
    }
    empty.value = false
    await renderChart(setOption, buildOption(progressData.achievementRate))
  } catch (e) {
    empty.value = true
    clear()
  } finally {
    loading.value = false
  }
}

watch(() => [store.revision, store.periodValue], load)
onMounted(load)
</script>

<style lang="scss" scoped>
/* container query 上下文：让内部栏数跟随「卡片宽度」而不是视口宽度 */
.target-box {
  container-type: inline-size;
  height: 100%;
}

/* 三栏：仪表盘 | 核心指标 | 品类进度；gap 收紧（32px -> 20px），
   让环形图与目标金额列表靠得更近，横向更紧凑 */
.target {
  display: grid;
  grid-template-columns: minmax(140px, 180px) minmax(150px, 1fr) minmax(210px, 1.4fr);
  /* 三栏同样要用 grid-template-areas 声明区域名：
     子元素已写了 grid-area: gauge/info/list，若此处不声明，浏览器会为
     这些未知区域名创建隐式轨道，把三栏布局撑坏（实测会多出 0px 列） */
  grid-template-areas: 'gauge info list';
  gap: 10px 20px;
  align-items: center;
  height: 100%;

  /* 无品类明细时收敛为两栏，不留下空列 */
  &--solo {
    grid-template-columns: minmax(140px, 180px) minmax(0, 1fr);
    grid-template-areas: 'gauge info';
  }

  /* 卡片被压窄时三栏已放不下，改为「仪表盘 + 核心指标」一行、品类进度整行展开。
     阈值 600px 按「卡片内容宽」计：7:5 并排时卡片 678px 减去内边距 48px 后为 630px，
     因此阈值必须小于 630，否则会过早堆叠、把整行卡片拉到 700px+ 高 */
  @container (max-width: 600px) {
    grid-template-columns: minmax(130px, 170px) minmax(0, 1fr);
    grid-template-areas:
      'gauge info'
      'list list';
    gap: 12px 20px;
    align-items: start;

    /* 品类进度整行展开后改双列排布，避免 7 个品类纵向拉出 400px+ 高度 */
    .target__list {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      column-gap: 24px;
    }
  }

  /* 极窄：单列堆叠 */
  @container (max-width: 420px) {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      'gauge'
      'info'
      'list';
    gap: 12px;

    .target__list {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .chart--gauge {
    grid-area: gauge;
  }

  &__info {
    grid-area: info;
    min-width: 0;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    font-size: 12.5px;
    color: var(--t-sub);
    padding: 7px 0;
    border-bottom: 1px dashed var(--bd-light);

    &:last-child {
      border-bottom: none;
    }

    b {
      color: var(--t-main);
      font-variant-numeric: tabular-nums;

      &.is-primary {
        color: var(--c-primary);
      }

      &.is-warning {
        color: var(--c-warning);
      }

      /* 已超额：追平任务已完成，用成功色而不是警告色 */
      &.is-done {
        color: var(--c-success);
      }
    }
  }

  /* 金额 + 状态补充：整块右对齐，避免 flex 三列把标签、金额、状态摊开 */
  &__value {
    display: inline-flex;
    align-items: baseline;
    gap: 6px;
    white-space: nowrap;
  }

  &__note {
    font-style: normal;
    font-size: 11px;
    line-height: 1;
    padding: 2px 5px;
    border-radius: 4px;
    color: var(--c-success);
    background: rgba(76, 149, 108, 0.12);
  }

  /* 品类进度：不设 max-height / overflow-y，全部品类完整自然展开，
     底部不再出现被裁切的半行文字 */
  &__list {
    grid-area: list;
    min-width: 0;
  }

  &__list-title {
    margin: 0 0 6px;
    font-size: 12px;
    color: var(--t-muted);
  }

  &__list-item {
    padding: 6px 0;
  }

  &__list-head,
  &__list-foot {
    display: flex;
    justify-content: space-between;
    gap: 8px;
    font-size: 12px;
    color: var(--t-sub);
  }

  &__list-foot {
    color: var(--t-muted);
  }
}

.chart--gauge {
  width: 100%;
  height: 200px;
}
</style>
