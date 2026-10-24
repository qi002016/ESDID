<template>
  <!-- A8 客单价区间分布直方图：订单数柱状 + 销售额折线（双轴） -->
  <ChartCard title="客单价区间分布" :subtitle="subtitle" :loading="loading" :empty="empty">
    <div ref="chartRef" class="chart chart--price"></div>
  </ChartCard>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import ChartCard from './ChartCard.vue'
import {
  COLORS,
  tooltipDivider,
  tooltipRow,
  tooltipTitle,
  verticalGradient
} from './chartTheme'
import { renderChart, useChart } from './useChart'
import { useDashboardStore } from '@/store/dashboard'
import { getPriceDistribution } from '@/api'
import { formatCompact, formatMoney, formatNumber, formatPercent, safeArray } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')

const { setOption, clear } = useChart(chartRef)

function buildOption(intervals) {
  const ranges = intervals.map((item) => item.range || '-')
  const counts = intervals.map((item) => item.orderCount ?? 0)
  const sales = intervals.map((item) => item.sales ?? 0)
  const rowMap = new Map(intervals.map((item) => [item.range, item]))

  // 找出订单数最多的区间做高亮（价格带主力）
  const maxIndex = counts.reduce((best, value, index) => (value > counts[best] ? index : best), 0)

  return {
    color: [COLORS.cyan, COLORS.purple],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const list = Array.isArray(params) ? params : [params]
        const range = list[0]?.axisValue
        const row = rowMap.get(range) || {}
        return (
          tooltipTitle(`￥${range}`) +
          tooltipDivider +
          tooltipRow('订单数', formatNumber(row.orderCount), COLORS.cyan) +
          tooltipRow('订单占比', formatPercent(row.orderRatio), COLORS.cyan) +
          tooltipRow('销售额', `￥${formatMoney(row.sales)}`, COLORS.purple)
        )
      }
    },
    // 图例居中靠上，右侧让给第二个 Y 轴的轴名（销售额），避免右上角重叠
    legend: { data: ['订单数', '销售额'], top: 0, left: 'center', itemGap: 24 },
    grid: { left: 8, right: 20, bottom: 0, top: 66, containLabel: true },
    xAxis: {
      type: 'category',
      data: ranges,
      axisLabel: { interval: 0, rotate: ranges.length > 5 ? 25 : 0 }
    },
    yAxis: [
      {
        type: 'value',
        name: '订单数',
        nameTextStyle: { padding: [0, 0, 0, 0] },
        axisLabel: { formatter: (value) => formatNumber(value) }
      },
      {
        type: 'value',
        name: '销售额(元)',
        nameTextStyle: { padding: [0, 4, 0, 0] },
        axisLabel: { formatter: (value) => formatCompact(value) },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '订单数',
        type: 'bar',
        // 柱宽固定 40px：6 个区间铺满整行时带宽约 258px，百分比会撑成笨重方块
        barMaxWidth: 40,
        data: counts.map((value, index) => ({
          value,
          itemStyle: {
            // 峰值区间用警示色突出，其余用青色渐变
            color: index === maxIndex ? COLORS.warning : verticalGradient(COLORS.cyan)
          }
        }))
      },
      {
        name: '销售额',
        type: 'line',
        yAxisIndex: 1,
        lineStyle: { width: 2, type: 'dashed', color: COLORS.purple },
        itemStyle: { color: COLORS.purple },
        data: sales
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    const data = await getPriceDistribution(store.statParams)
    const intervals = safeArray(data?.intervals)
    subtitle.value = data
      ? `共 ${formatNumber(data.totalOrders)} 单 · ￥${formatMoney(data.totalSales)}`
      : ''
    if (intervals.length === 0) {
      empty.value = true
      clear()
      return
    }
    empty.value = false
    await renderChart(setOption, buildOption(intervals))
  } catch (e) {
    empty.value = true
    clear()
  } finally {
    loading.value = false
  }
}

watch(() => store.revision, load)
onMounted(load)
</script>

<style lang="scss" scoped>
.chart--price {
  height: 280px;
  width: 100%;
}
</style>
