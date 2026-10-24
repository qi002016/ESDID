<template>
  <!-- 销售趋势：GMV（主色）与订单量（紫）双线 + 渐变面积 -->
  <ChartCard
    title="销售趋势"
    :subtitle="subtitle"
    :loading="loading"
    :empty="empty"
    empty-height="320px"
  >
    <template #extra>
      <SegmentedControl v-model="granularity" :options="granularityOptions" size="small" />
    </template>

    <div ref="chartRef" class="chart chart--trend"></div>
  </ChartCard>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import ChartCard from './ChartCard.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import { renderChart, useChart } from './useChart'
import {
  COLORS,
  TEXT_MUTED,
  TEXT_SUB,
  tooltipRow,
  tooltipTitle,
  verticalGradient
} from './chartTheme'
import { useDashboardStore } from '@/store/dashboard'
import { getOrdersStats } from '@/api'
import { formatCompact, formatMoney, formatNumber, safeArray } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')

const { setOption, clear } = useChart(chartRef)

const granularity = ref('day')
const granularityOptions = [
  { label: '日', value: 'day' },
  { label: '周', value: 'week' },
  { label: '月', value: 'month' }
]
const granularityText = computed(
  () => ({ day: '按天', week: '按周', month: '按月' })[granularity.value] || '按天'
)

function buildOption(items) {
  const periods = items.map((item) => item.period)
  const sales = items.map((item) => item.sales ?? 0)
  const orders = items.map((item) => item.orders ?? 0)

  return {
    // 图例居中靠上：右侧让给第二个 Y 轴的轴名（订单量），两者在右上角不再互压
    legend: { data: ['GMV', '订单量'], top: 0, left: 'center', itemGap: 24 },
    // grid.top 留足 64px：图例(约 20px) + 轴名(约 14px) + 安全间距
    grid: { left: 4, right: 12, top: 64, bottom: 0, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: TEXT_MUTED, type: 'dashed' } },
      formatter: (params) => {
        const list = Array.isArray(params) ? params : [params]
        const index = list[0]?.dataIndex ?? 0
        return (
          tooltipTitle(periods[index]) +
          tooltipRow('GMV', `¥${formatMoney(sales[index])}`, COLORS.primary) +
          tooltipRow('订单量', formatNumber(orders[index]), COLORS.purple)
        )
      }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: periods,
      axisLabel: {
        color: TEXT_SUB,
        hideOverlap: true,
        formatter: (value) => String(value).slice(5)
      }
    },
    yAxis: [
      {
        type: 'value',
        name: 'GMV',
        // 轴名贴轴顶端，与居中图例保持垂直分离
        nameTextStyle: { padding: [0, 0, 0, 0] },
        axisLabel: { formatter: (value) => formatCompact(value) }
      },
      {
        type: 'value',
        name: '订单量',
        // 右侧轴名默认右对齐、容易顶到右上角：右移 4px 内缩，避开图例文字
        nameTextStyle: { padding: [0, 4, 0, 0] },
        splitLine: { show: false },
        axisLabel: { formatter: (value) => formatNumber(value) }
      }
    ],
    series: [
      {
        name: 'GMV',
        type: 'line',
        yAxisIndex: 0,
        smooth: true,
        symbol: 'circle',
        symbolSize: 0,
        showSymbol: false,
        lineStyle: { width: 2.5, color: COLORS.primary },
        itemStyle: { color: COLORS.primary },
        areaStyle: { color: verticalGradient(COLORS.primary, 0.28, 0.01) },
        emphasis: { focus: 'series', scale: 1.4 },
        data: sales
      },
      {
        name: '订单量',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 0,
        showSymbol: false,
        lineStyle: { width: 2.5, color: COLORS.purple },
        itemStyle: { color: COLORS.purple },
        areaStyle: { color: verticalGradient(COLORS.purple, 0.2, 0.01) },
        emphasis: { focus: 'series', scale: 1.4 },
        data: orders
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    const data = await getOrdersStats({ ...store.statParams, granularity: granularity.value })
    const items = safeArray(data?.items)
    subtitle.value = `${granularityText.value} · ${store.startDate} ~ ${store.endDate}`
    if (items.length === 0) {
      empty.value = true
      clear()
      return
    }
    empty.value = false
    await renderChart(setOption, buildOption(items))
  } catch (e) {
    empty.value = true
    clear()
  } finally {
    loading.value = false
  }
}

watch(() => [store.revision, granularity.value], load)
onMounted(load)
</script>

<style lang="scss" scoped>
/* 高度自适应卡片：与「销售目标达成率」7:5 并排时卡片被同行撑高，
   图表跟着撑满，避免下方留出整片空白；单行摆放时用 min-height 兜底 */
.chart--trend {
  height: 100%;
  min-height: 320px;
  width: 100%;
}
</style>
