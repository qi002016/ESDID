<template>
  <!-- A10 24 小时下单时段分布 -->
  <ChartCard title="24 小时下单时段分布" :subtitle="subtitle" :loading="loading" :empty="empty">
    <div ref="chartRef" class="chart chart--hourly"></div>
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
import { getHourlyStats } from '@/api'
import { formatMoney, formatNumber, safeArray } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')

const { setOption, clear } = useChart(chartRef)

function buildOption(hours) {
  // 文档：hours 固定 24 个元素，无订单时段返回 0；这里按 hour 补齐，防止缺元素导致错位
  const map = new Map(hours.map((item) => [Number(item.hour), item]))
  const full = Array.from({ length: 24 }, (_, hour) => {
    const row = map.get(hour) || {}
    return { hour, orderCount: row.orderCount ?? 0, sales: row.sales ?? 0 }
  })
  const labels = full.map((item) => `${String(item.hour).padStart(2, '0')}:00`)
  const counts = full.map((item) => item.orderCount)
  const salesArr = full.map((item) => item.sales)

  /**
   * 两条序列各自找峰值——**不能只标一个**。
   * 订单量峰值和销售额峰值常常不在同一小时（例如本月：20:00 单量最高 45 单，
   * 但 10:00 销售额最高 ￥119,477，20:00 只有 ￥113,873），
   * 只标其中一个，另一个的顶点就会「跑到标注外面」，看起来像折线画歪了。
   * 所以：柱子标订单峰值、折线标销售额峰值，各自认领。
   */
  const peakOrders = counts.reduce((best, value, index) => (value > counts[best] ? index : best), 0)
  const peakSales = salesArr.reduce((best, value, index) => (value > salesArr[best] ? index : best), 0)
  const samePeak = peakOrders === peakSales
  /**
   * 折线峰值文字：两者同小时时并入柱顶的「峰值」，不再重复标；
   * 相差 1 小时时（柱顶标签朝上、折线标签朝下，仍在同一高度带）也省略文字，只留圆点，避免压字。
   */
  const showSalesLabel = !samePeak && Math.abs(peakSales - peakOrders) >= 2

  return {
    color: [COLORS.primary, COLORS.purple],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const list = Array.isArray(params) ? params : [params]
        const row = full[list[0]?.dataIndex] || {}
        return (
          tooltipTitle(`${row.hour}:00 - ${row.hour}:59`) +
          tooltipDivider +
          tooltipRow('订单量', formatNumber(row.orderCount), COLORS.primary) +
          tooltipRow('销售额', `￥${formatMoney(row.sales)}`, COLORS.purple)
        )
      }
    },
    // 图例居中靠上，右侧让给第二个 Y 轴的轴名（销售额），避免右上角互相重叠
    legend: { data: ['订单量', '销售额'], top: 0, left: 'center', itemGap: 24 },
    // grid.top 66px：图例(约 14px) + 轴名 + 折线峰值圆点的上浮余量
    grid: { left: 8, right: 16, bottom: 0, top: 66, containLabel: true },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { interval: 1 }
    },
    yAxis: [
      {
        type: 'value',
        name: '订单量',
        nameTextStyle: { padding: [0, 0, 0, 0] },
        axisLabel: { formatter: (value) => formatNumber(value) }
      },
      {
        type: 'value',
        name: '销售额',
        nameTextStyle: { padding: [0, 4, 0, 0] },
        splitLine: { show: false },
        // 留 8% 顶部余量：销售额峰值贴着轴顶时，折线上的峰值圆点会被网格裁成半圆
        max: (value) => Math.ceil((value.max * 1.08) / 10000) * 10000
      }
    ],
    series: [
      {
        name: '订单量',
        type: 'bar',
        // 24 根柱子：柱宽按带宽百分比，宽屏下不至于细成针、窄屏下也不会挤在一起
        barMaxWidth: '36%',
        barMinWidth: 3,
        itemStyle: {
          color: verticalGradient(COLORS.primary)
        },
        // 峰值时段：橙色高亮 + 柱顶标注，辅助营销排期。
        // 这里直接用数据项自带的 label（peakOrders 索引已经算出来了），
        // 比 markPoint 的 type:'max' 更直接，也不受「data 是对象数组」的取值差异影响。
        data: counts.map((value, index) => ({
          value,
          itemStyle: index === peakOrders ? { color: COLORS.warning } : undefined,
          label:
            index === peakOrders
              ? {
                  show: true,
                  position: 'top',
                  distance: 4,
                  formatter: samePeak ? '峰值' : '订单峰值',
                  color: COLORS.warning,
                  fontSize: 11,
                  fontWeight: 600
                }
              : { show: false }
        }))
      },
      {
        name: '销售额',
        type: 'line',
        yAxisIndex: 1,
        symbol: 'none',
        lineStyle: { width: 2, color: COLORS.purple },
        itemStyle: { color: COLORS.purple },
        data: salesArr,
        // 销售额自己的峰值打点。右侧轴已留 8% 顶部余量，圆点上方是空白带，
        // 标签朝上不会被裁切、也不会压到曲线；加底色是防止曲线从字后面穿过。
        markPoint: {
          symbol: 'circle',
          symbolSize: 9,
          itemStyle: { color: COLORS.warning, borderColor: '#fff', borderWidth: 1.5 },
          label: {
            show: showSalesLabel,
            position: 'top',
            distance: 6,
            formatter: '销售额峰值',
            color: COLORS.warning,
            fontSize: 11,
            fontWeight: 600,
            backgroundColor: 'rgba(253,252,248,0.92)',
            padding: [2, 5],
            borderRadius: 3
          },
          data: [{ name: '销售额峰值', coord: [peakSales, salesArr[peakSales]] }]
        }
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    const data = await getHourlyStats(store.statParams)
    const hours = safeArray(data?.hours)
    // 全部为 0 视为无数据
    const hasData = hours.some((item) => (item.orderCount ?? 0) > 0 || (item.sales ?? 0) > 0)
    subtitle.value = `${store.startDate} ~ ${store.endDate}`
    if (hours.length === 0 || !hasData) {
      empty.value = true
      clear()
      return
    }
    empty.value = false
    await renderChart(setOption, buildOption(hours))
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
.chart--hourly {
  height: 280px;
  width: 100%;
}
</style>
