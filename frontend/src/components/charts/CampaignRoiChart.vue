<template>
  <!-- 促销活动效果分析：各活动 ROI（活动期 / 非活动期）横向条形对比 -->
  <ChartCard
    title="促销活动效果分析"
    :subtitle="subtitle"
    :loading="loading"
    :empty="empty"
    empty-height="300px"
  >
    <div ref="chartRef" class="chart chart--roi"></div>
  </ChartCard>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import ChartCard from './ChartCard.vue'
import { renderChart, useChart } from './useChart'
import {
  COLORS,
  TEXT_SUB,
  horizontalGradient,
  tooltipDivider,
  tooltipRow,
  tooltipTitle,
  withAlpha
} from './chartTheme'
import { useDashboardStore } from '@/store/dashboard'
import { getCampaignEffect } from '@/api'
import { formatMoney, formatNumber, formatPercent } from '@/utils/format'
import dayjs from 'dayjs'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')
const campaigns = ref([])

const { setOption, clear } = useChart(chartRef)

/**
 * 活动窗口配置（接口 A15 需要显式传入活动期区间）
 * 覆盖「本年度 + 上一年度」的大促窗口，任一年度无数据时该条形自动隐藏；
 * 另附「当前筛选区间」作为兜底活动，保证任何数据范围都有可对比的条形
 */
function buildWindows() {
  const year = dayjs(store.endDate).year()
  const windows = []
  ;[year, year - 1].forEach((y) => {
    windows.push(
      { name: `618 大促 · ${y}`, start: `${y}-06-01`, end: `${y}-06-18` },
      { name: `双 11 · ${y}`, start: `${y}-11-01`, end: `${y}-11-11` },
      { name: `双 12 · ${y}`, start: `${y}-12-01`, end: `${y}-12-12` },
      { name: `黑五促销 · ${y}`, start: `${y}-11-24`, end: `${y}-11-30` }
    )
  })
  windows.push({ name: '当前筛选区间', start: store.startDate, end: store.endDate })
  return windows
}

/** ROI = 活动期 GMV / 非活动期 GMV（倍率）；<1 视为未跑赢日常 */
function roiColor(roi) {
  if (roi >= 1.3) return COLORS.success
  if (roi >= 1) return COLORS.primary
  return COLORS.danger
}

function buildOption(rows) {
  // 横向条形：y 轴自下而上绘制，因此按 ROI 升序排列，最大值显示在最上方
  const sorted = [...rows].sort((a, b) => a.roi - b.roi)
  const rowMap = new Map(sorted.map((item) => [item.name, item]))

  return {
    // 顶部留 34px：给「盈亏平衡 1.0x」参考线标签腾出空间，否则标签默认贴在绘制区顶端被裁掉
    grid: { left: 4, right: 76, top: 34, bottom: 0, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(44,110,93,0.06)' } },
      formatter: (params) => {
        const row = rowMap.get(params[0]?.name) || {}
        return (
          tooltipTitle(row.name) +
          tooltipRow('活动期 GMV', `¥${formatMoney(row.activitySales)}`) +
          tooltipRow('非活动期 GMV', `¥${formatMoney(row.nonActivitySales)}`) +
          tooltipRow('ROI 倍率', `${row.roi.toFixed(2)}x`, roiColor(row.roi)) +
          tooltipDivider +
          tooltipRow('GMV 增长', formatPercent(row.salesGrowthRate)) +
          tooltipRow('订单量增长', formatPercent(row.ordersGrowthRate)) +
          tooltipRow('促销拉动率', formatPercent(row.promotionPullRate))
        )
      }
    },
    xAxis: {
      type: 'value',
      name: 'ROI 倍率',
      axisLabel: { formatter: '{value}x' }
    },
    yAxis: {
      type: 'category',
      data: sorted.map((item) => item.name),
      // 104px：容纳「黑五促销 · 2025」这类 8 字标签，88px 会截成「黑五促销 · 2…」
      axisLabel: { color: TEXT_SUB, width: 104, overflow: 'truncate' }
    },
    series: [
      {
        type: 'bar',
        // 横向条形：条宽按行高百分比自适应，活动多时也不会细成针
        barMaxWidth: '60%',
        // ROI = 1 参考线：低于它的活动等于没跑赢日常
        markLine: {
          silent: true,
          symbol: 'none',
          label: {
            show: true,
            // 竖线的 end 端在绘制区顶部：配合 grid.top 留白 + 6px 间距，标签完整可见
            position: 'end',
            distance: 6,
            formatter: '盈亏平衡 1.0x',
            color: COLORS.warning,
            fontSize: 11
          },
          lineStyle: { type: 'dashed', color: withAlpha(COLORS.warning, 0.8) },
          data: [{ xAxis: 1 }]
        },
        itemStyle: { borderRadius: [0, 6, 6, 0] },
        label: {
          show: true,
          position: 'right',
          color: TEXT_SUB,
          fontSize: 12,
          formatter: (params) => `${params.value.toFixed(2)}x`
        },
        data: sorted.map((item) => ({
          value: Number(item.roi.toFixed(2)),
          itemStyle: { color: horizontalGradient(roiColor(item.roi), 0.35, 1) }
        }))
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    const windows = buildWindows()
    // A15 每次只返回一个活动期，这里并行请求得到「各活动」的对比数据
    const results = await Promise.all(
      windows.map((window) =>
        getCampaignEffect({
          // 透传全局筛选（品类 / 支付方式 / 用户类型 / 促销标记），仅日期用活动窗口覆盖
          ...store.statParams,
          startDate: window.start,
          endDate: window.end
        }).then((data) => ({ window, data }))
        .catch(() => ({ window, data: null }))
      )
    )

    const rows = results
      .filter((item) => (item.data?.activity?.sales ?? 0) > 0)
      .map((item) => {
        const activitySales = item.data.activity?.sales ?? 0
        const nonActivitySales = item.data.nonActivity?.sales ?? 0
        // 非活动期无数据时无法计算 ROI，直接剔除，避免出现无意义的极大值
        if (nonActivitySales <= 0) return null
        return {
          name: item.window.name,
          activitySales,
          nonActivitySales,
          roi: activitySales / nonActivitySales,
          salesGrowthRate: item.data.salesGrowthRate ?? null,
          ordersGrowthRate: item.data.ordersGrowthRate ?? null,
          promotionPullRate: item.data.promotionPullRate ?? null
        }
      })
      .filter(Boolean)

    subtitle.value = rows.length
      ? `活动期 vs 非活动期 ROI 倍率 · 共 ${formatNumber(rows.length)} 个活动`
      : ''

    if (rows.length === 0) {
      empty.value = true
      clear()
      return
    }
    campaigns.value = rows
    empty.value = false
    await renderChart(setOption, buildOption(rows))
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
.chart--roi {
  height: 300px;
  width: 100%;
}
</style>
