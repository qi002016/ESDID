<template>
  <!-- RFM 用户分层：柱状（渐变）/ 环形 切换 -->
  <ChartCard
    title="RFM 用户分层"
    :subtitle="subtitle"
    :loading="loading"
    :empty="empty"
    empty-height="300px"
  >
    <template #extra>
      <SegmentedControl v-model="chartType" :options="typeOptions" size="small" />
    </template>

    <!-- 分层口径单独一行：半宽卡片下副标题会省略，阈值必须完整可见 -->
    <p v-if="rfmThresholds" class="rfm__caption">
      分层口径：R ≤ {{ rfmThresholds.rDays ?? '-' }} 天为近期活跃 · F ≥
      {{ rfmThresholds.fCount ?? '-' }} 次为高频 · M ≥ ￥{{ formatMoney(rfmThresholds.mAmount) }} 为高额
    </p>

    <div class="rfm" :class="{ 'rfm--pie': chartType === 'pie' }">
      <div ref="chartRef" class="chart chart--rfm"></div>

      <!-- 环形形态下，图例改为右侧明细列表：既替代图例，又填满整行宽度 -->
      <ul v-if="chartType === 'pie'" class="rfm__list">
        <li v-for="(item, index) in segmentRows" :key="item.segment" class="rfm__item">
          <i class="rfm__dot" :style="{ background: segmentColor(item.segment, index) }"></i>
          <span class="rfm__name" :title="item.segment">{{ item.segment || '-' }}</span>
          <span class="rfm__track">
            <i
              :style="{
                width: `${item.percent}%`,
                background: segmentColor(item.segment, index)
              }"
            ></i>
          </span>
          <b class="rfm__value">{{ formatNumber(item.userCount) }}</b>
          <span class="rfm__percent">{{ item.percent.toFixed(1) }}%</span>
        </li>
      </ul>
    </div>
  </ChartCard>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import ChartCard from './ChartCard.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import { renderChart, useChart } from './useChart'
import {
  COLORS,
  FONT_FAMILY,
  TEXT_MUTED,
  horizontalGradient,
  tooltipDivider,
  tooltipRow,
  tooltipTitle
} from './chartTheme'
import { useDashboardStore } from '@/store/dashboard'
import { getRfmAnalysis } from '@/api'
import { formatCompact, formatMoney, formatNumber, formatPercent, safeArray } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')
const segments = ref([])
/** 后端返回的分层阈值（R/F/M），放在图上方单独一行，避免挤在副标题里被截断 */
const rfmThresholds = ref(null)

const { setOption, clear } = useChart(chartRef)

const chartType = ref('bar')
const typeOptions = [
  { label: '柱状图', value: 'bar' },
  { label: '环形图', value: 'pie' }
]

/** 分层语义配色：价值越高越"重"色 */
const SEGMENT_COLOR = {
  重要价值客户: COLORS.primary,
  重要保持客户: COLORS.purple,
  重要发展客户: COLORS.cyan,
  一般价值客户: COLORS.success,
  一般保持客户: COLORS.warning,
  一般发展客户: COLORS.slate,
  流失风险客户: COLORS.danger,
  流失客户: COLORS.danger
}

function segmentColor(name, index) {
  const palette = [COLORS.primary, COLORS.purple, COLORS.cyan, COLORS.success, COLORS.warning, COLORS.slate, COLORS.danger]
  return SEGMENT_COLOR[name] || palette[index % palette.length]
}

/** 环形形态右侧明细：用户数 + 占比，与图形共用同一份数据 */
const segmentRows = computed(() => {
  const rows = segments.value
  const total = rows.reduce((sum, item) => sum + (item.userCount ?? 0), 0)
  return rows.map((item) => ({
    ...item,
    percent: total ? ((item.userCount ?? 0) / total) * 100 : 0
  }))
})

function buildBarOption() {
  const rows = segments.value
  const names = rows.map((item) => item.segment || '-')
  const users = rows.map((item) => item.userCount ?? 0)
  const rowMap = new Map(rows.map((item) => [item.segment, item]))

  /*
    柱宽固定 40px：RFM 只有 4~8 个分层、又整行铺满，带宽极大
    （实测 4 层时约 388px），用带宽百分比会把柱子撑成 170px+ 的笨重方块。
  */
  const barMaxWidth = 40

  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(44,110,93,0.06)' } },
      formatter: (params) => {
        const row = rowMap.get(params[0]?.axisValue) || {}
        return (
          tooltipTitle(row.segment) +
          tooltipRow('用户数', formatNumber(row.userCount)) +
          tooltipRow('销售额', `¥${formatMoney(row.sales)}`) +
          tooltipRow('销售额占比', formatPercent(row.salesRatio)) +
          tooltipDivider +
          tooltipRow('客单价', `¥${formatMoney(row.avgOrderValue)}`) +
          tooltipRow('平均频次', row.avgFrequency ?? '--')
        )
      }
    },
    // 顶部留 42px：y 轴轴名「用户数」默认 nameGap 15，24px 时会被画布上边缘裁掉
    grid: { left: 4, right: 12, top: 42, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: { interval: 0, rotate: names.length > 4 ? 18 : 0 }
    },
    yAxis: { type: 'value', name: '用户数', axisLabel: { formatter: (value) => formatCompact(value) } },
    series: [
      {
        name: '用户数',
        type: 'bar',
        barMaxWidth,
        barMinWidth: 14,
        // 每根柱子按分层语义使用不同渐变
        data: users.map((value, index) => ({
          value,
          itemStyle: {
            borderRadius: [6, 6, 0, 0],
            color: horizontalGradient(segmentColor(names[index], index), 0.45, 1)
          }
        })),
        label: {
          show: true,
          position: 'top',
          color: TEXT_MUTED,
          fontSize: 11,
          formatter: (params) => formatNumber(params.value)
        }
      }
    ]
  }
}

function buildPieOption() {
  const rows = segments.value
  const total = rows.reduce((sum, item) => sum + (item.userCount ?? 0), 0)
  return {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        const row = params.data?.row || {}
        return (
          tooltipTitle(row.segment) +
          tooltipRow('用户数', formatNumber(row.userCount), params.color) +
          tooltipRow('用户占比', `${params.percent}%`) +
          tooltipDivider +
          tooltipRow('销售额', `¥${formatMoney(row.sales)}`) +
          tooltipRow('销售额占比', formatPercent(row.salesRatio))
        )
      }
    },
    // 图例由右侧明细列表承担，环形图占满左侧画布
    graphic: [
      {
        type: 'text',
        left: 'center',
        top: '43%',
        style: { text: formatNumber(total), fontSize: 22, fontWeight: 700, fill: '#262a30', fontFamily: FONT_FAMILY }
      },
      {
        type: 'text',
        left: 'center',
        top: '55%',
        style: { text: '总用户数', fontSize: 12, fill: TEXT_MUTED, fontFamily: FONT_FAMILY }
      }
    ],
    series: [
      {
        type: 'pie',
        radius: ['54%', '76%'],
        center: ['50%', '50%'],
        padAngle: 2,
        itemStyle: { borderColor: '#fdfcf8', borderWidth: 3, borderRadius: 4 },
        label: { show: false },
        labelLine: { show: false },
        data: rows.map((item, index) => ({
          name: item.segment || '-',
          value: item.userCount ?? 0,
          row: item,
          itemStyle: { color: segmentColor(item.segment, index) }
        }))
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    const data = await getRfmAnalysis(store.statParams)
    segments.value = safeArray(data?.segments)
    rfmThresholds.value = data?.thresholds || null
    // 副标题只留窗口与总人数；R/F/M 阈值改由图上方口径行展示，防止半宽卡片下被省略号截断
    subtitle.value = data
      ? [`窗口 ${data.windowStart || ''} ~ ${data.windowEnd || ''}`, `总用户 ${formatNumber(data.totalUsers)}`]
          .filter(Boolean)
          .join(' · ')
      : ''
    if (segments.value.length === 0) {
      empty.value = true
      clear()
      return
    }
    empty.value = false
    await renderChart(setOption, chartType.value === 'pie' ? buildPieOption() : buildBarOption())
  } catch (e) {
    empty.value = true
    clear()
  } finally {
    loading.value = false
  }
}

// 形态切换只重绘，不重新请求
watch(chartType, () => {
  if (!empty.value) setOption(chartType.value === 'pie' ? buildPieOption() : buildBarOption())
})
watch(() => store.revision, load)
onMounted(load)
</script>

<style lang="scss" scoped>
/* 分层口径行：阈值完整可见，不参与图例/副标题的省略号截断 */
.rfm__caption {
  margin: 0 0 10px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--t-muted);
}

/* 环形形态：左图右明细，铺满整行；柱状形态保持单图占满宽度 */
.rfm {
  height: 100%;

  &--pie {
    display: grid;
    grid-template-columns: minmax(0, 300px) minmax(0, 1fr);
    gap: 16px 32px;
    align-items: center;

    @media (max-width: 1000px) {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  &__list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  &__item {
    display: grid;
    grid-template-columns: 8px minmax(70px, 130px) minmax(60px, 1fr) auto 58px;
    align-items: center;
    gap: 10px;
    padding: 7px 0;
    font-size: 12.5px;
    border-bottom: 1px dashed var(--bd-light);

    &:last-child {
      border-bottom: none;
    }
  }

  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 3px;
  }

  &__name {
    color: var(--t-main);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__track {
    height: 6px;
    border-radius: 999px;
    background: var(--bg-head);
    overflow: hidden;

    i {
      display: block;
      height: 100%;
      border-radius: 999px;
      transition: width 0.3s ease;
    }
  }

  &__value {
    color: var(--t-main);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  &__percent {
    text-align: right;
    color: var(--t-sub);
    font-variant-numeric: tabular-nums;
  }
}

.chart--rfm {
  width: 100%;
  height: 300px;
}

/* 窄屏堆叠后收窄并居中 */
@media (max-width: 1000px) {
  .rfm--pie .chart--rfm {
    max-width: 320px;
    justify-self: center;
  }
}
</style>
