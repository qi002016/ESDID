<template>
  <!-- A9 品类贡献帕累托图：柱状图（销售额）+ 累计占比折线（双轴） -->
  <ChartCard title="品类贡献分析（帕累托）" :subtitle="subtitle" :loading="loading" :empty="empty">
    <div ref="chartRef" class="chart chart--pareto"></div>
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
import { getCategoryContribution } from '@/api'
import { formatCompact, formatMoney, formatNumber, formatPercent, safeArray } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')

const { setOption, clear } = useChart(chartRef)

function buildOption(items, totalSales) {
  const categories = items.map((item) => item.category || '-')
  const sales = items.map((item) => item.sales ?? 0)
  // cumulativeRatio 为 0~1 小数，折线按百分比展示（文档示例：0.36 -> 36%）
  const cumulative = items.map((item) =>
    item.cumulativeRatio === null || item.cumulativeRatio === undefined
      ? null
      : Number((item.cumulativeRatio * 100).toFixed(2))
  )
  const ratioMap = new Map(items.map((item) => [item.category, item]))

  return {
    color: [COLORS.primary, COLORS.purple],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const list = Array.isArray(params) ? params : [params]
        const name = list[0]?.axisValue
        const row = ratioMap.get(name) || {}
        return (
          tooltipTitle(name) +
          tooltipDivider +
          tooltipRow('销售额', `￥${formatMoney(row.sales)}`, COLORS.primary) +
          tooltipRow('占比', formatPercent(row.salesRatio), COLORS.primary) +
          tooltipRow('累计占比', formatPercent(row.cumulativeRatio), COLORS.purple)
        )
      }
    },
    // 图例居中靠上，右侧让给第二个 Y 轴的轴名（累计占比）
    legend: { data: ['销售额', '累计占比'], top: 0, left: 'center', itemGap: 24 },
    grid: { left: 8, right: 20, bottom: 0, top: 66, containLabel: true },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { interval: 0, rotate: categories.length > 6 ? 30 : 0 }
    },
    yAxis: [
      {
        type: 'value',
        name: '销售额(元)',
        nameTextStyle: { padding: [0, 0, 0, 0] },
        axisLabel: { formatter: (value) => formatCompact(value) }
      },
      {
        type: 'value',
        name: '累计占比',
        nameTextStyle: { padding: [0, 4, 0, 0] },
        min: 0,
        max: 100,
        axisLabel: { formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '销售额',
        type: 'bar',
        // 柱宽固定 40px：本图仅 6 个品类又整行铺满，带宽极大（约 258px），
        // 用带宽百分比会把柱子撑成 100px+ 的笨重方块
        barMaxWidth: 40,
        yAxisIndex: 0,
        itemStyle: {
          color: verticalGradient(COLORS.primary)
        },
        data: sales
      },
      {
        name: '累计占比',
        type: 'line',
        yAxisIndex: 1,
        symbolSize: 7,
        lineStyle: { width: 2, color: COLORS.purple },
        itemStyle: { color: COLORS.purple },
        // 80% 帕累托参考线：直观看出哪些品类贡献了主要销售额
        markLine: {
          silent: true,
          symbol: 'none',
          label: { formatter: '80%', color: COLORS.warning },
          lineStyle: { type: 'dashed', color: COLORS.warning },
          data: [{ yAxis: 80 }]
        },
        data: cumulative
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    // A9 文档仅声明 startDate / endDate；这里透传全局筛选，保证切换筛选项时图表同步变化
    const data = await getCategoryContribution(store.statParams)
    const items = safeArray(data?.items)
    const total = data?.totalSales
    subtitle.value = `总销售额 ￥${formatMoney(total)} · 共 ${formatNumber(items.length)} 个品类`
    if (items.length === 0) {
      empty.value = true
      clear()
      return
    }
    empty.value = false
    await renderChart(setOption, buildOption(items, total))
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
.chart--pareto {
  height: 320px;
  width: 100%;
}
</style>
