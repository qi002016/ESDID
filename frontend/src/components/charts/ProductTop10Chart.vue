<template>
  <!-- A4 商品 TOP10 横向柱状图，支持销售额 / 销量切换 -->
  <ChartCard title="商品 TOP10" :subtitle="subtitle" :loading="loading" :empty="empty">
    <template #extra>
      <SegmentedControl v-model="store.rankBy" :options="rankOptions" size="small" />
    </template>

    <div ref="chartRef" class="chart chart--rank"></div>
  </ChartCard>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import ChartCard from './ChartCard.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import {
  COLORS,
  TEXT_SUB,
  horizontalGradient,
  tooltipDivider,
  tooltipRow,
  tooltipTitle
} from './chartTheme'
import { renderChart, useChart } from './useChart'
import { useDashboardStore } from '@/store/dashboard'
import { getProductRanking } from '@/api'
import { formatCompact, formatMoney, formatNumber, safeArray } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')

const { setOption, clear } = useChart(chartRef)

const rankLabel = computed(() => (store.rankBy === 'quantity' ? '销量(件)' : '销售额(元)'))

/** 排序口径切换：小圆角分段控件（与其它卡片保持一致） */
const rankOptions = [
  { label: '按销售额', value: 'sales' },
  { label: '按销量', value: 'quantity' }
]

/** 横向柱状图：ECharts 的 y 轴自上而下绘制，因此数据需反转，保证 TOP1 在最上方 */
function buildOption(items) {
  const sorted = [...items].sort((a, b) => {
    const av = store.rankBy === 'quantity' ? a.quantity ?? 0 : a.sales ?? 0
    const bv = store.rankBy === 'quantity' ? b.quantity ?? 0 : b.sales ?? 0
    return av - bv
  })
  const names = sorted.map((item) => item.productName || '-')
  const values = sorted.map((item) =>
    store.rankBy === 'quantity' ? item.quantity ?? 0 : item.sales ?? 0
  )
  // 用主色渐变 + TOP3 警示色区分，突出头部商品
  const barData = values.map((value, index) => ({
    value,
    itemStyle: {
      color: index >= values.length - 3 ? COLORS.warning : horizontalGradient(COLORS.primary)
    }
  }))

  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const item = params[0]
        // sorted 为升序数组，dataIndex 与其索引一一对应（最大的商品显示在最上方）
        const row = sorted[item.dataIndex] || {}
        const sales = `￥${formatMoney(row.sales)}`
        return (
          tooltipTitle(item.name) +
          tooltipDivider +
          tooltipRow('销售额', sales, COLORS.primary) +
          tooltipRow('销量', formatNumber(row.quantity), COLORS.purple)
        )
      }
    },
    grid: { left: 8, right: 60, top: 10, bottom: 0, containLabel: true },
    xAxis: {
      type: 'value',
      name: rankLabel.value,
      axisLabel: { formatter: (value) => formatCompact(value) }
    },
    yAxis: {
      type: 'category',
      data: names,
      axisLabel: { width: 110, overflow: 'truncate' }
    },
    series: [
      {
        type: 'bar',
        // 横向条形：条宽按行高百分比自适应，10 行时约 17px，与原先固定 16px 相当
        barMaxWidth: '58%',
        data: barData,
        label: {
          show: true,
          position: 'right',
          color: TEXT_SUB,
          formatter: (params) =>
            store.rankBy === 'quantity'
              ? formatNumber(params.value)
              : formatCompact(params.value)
        }
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    // A4：rankBy + limit，配合全局筛选参数
    const list = await getProductRanking({ ...store.statParams, rankBy: store.rankBy, limit: 10 })
    // 文档：该接口 data 直接是数组；这里同时兼容 { items: [] } 的包装
    const items = safeArray(list?.items || list)
    subtitle.value = `口径：${store.rankBy === 'quantity' ? '销量' : '销售额'} · ${store.startDate} ~ ${store.endDate}`
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

watch(() => [store.revision, store.rankBy], load)
onMounted(load)
</script>

<style lang="scss" scoped>
.chart--rank {
  height: 320px;
  width: 100%;
}
</style>
