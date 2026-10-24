<template>
  <!-- 地域销售分布：中国地图热力图（左）+ 排行榜列表（右），可切换地图/排行视图 -->
  <ChartCard
    title="地域销售分布"
    :subtitle="subtitle"
    :loading="loading"
    :empty="empty"
    :empty-text="emptyText"
    empty-height="320px"
  >
    <template #extra>
      <SegmentedControl v-model="viewType" :options="viewOptions" size="small" />
    </template>

    <div class="region">
      <div ref="chartRef" class="chart chart--region"></div>

      <!-- 右侧排行榜：始终展示 Top 省份，与地图互为补充 -->
      <aside class="region__rank">
        <div class="region__rank-head">
          <span>省份排行</span>
          <span class="region__rank-total num">¥{{ formatCompact(totalSales) }}</span>
        </div>
        <ul class="region__rank-list">
          <li v-for="(item, index) in topRegions" :key="item.region" class="region__rank-item">
            <span class="region__rank-no" :class="{ 'is-top': index < 3 }">{{ index + 1 }}</span>
            <span class="region__rank-name">{{ item.region }}</span>
            <span class="region__rank-bar">
              <i :style="{ width: `${barWidth(item)}%` }"></i>
            </span>
            <span class="region__rank-value num">¥{{ formatCompact(item.sales) }}</span>
          </li>
          <li v-if="!topRegions.length" class="region__rank-empty">暂无数据</li>
        </ul>
      </aside>
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
  TEXT_SUB,
  horizontalGradient,
  tooltipDivider,
  tooltipRow,
  tooltipTitle,
  withAlpha
} from './chartTheme'
import { useDashboardStore } from '@/store/dashboard'
import { getRegionSales } from '@/api'
import { formatCompact, formatMoney, formatNumber, formatPercent, safeArray } from '@/utils/format'
// ECharts 5 不再内置地图，需自行 registerMap
import chinaGeoJson from '@/assets/geo/china.json'

const MAP_NAME = 'china'
const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const emptyText = ref('暂无数据')
const subtitle = ref('')
const regions = ref([])

const { setOption, clear, echarts } = useChart(chartRef)

const viewType = ref('map')
const viewOptions = [
  { label: '地图', value: 'map' },
  { label: '排行', value: 'bar' }
]

try {
  echarts.registerMap(MAP_NAME, chinaGeoJson)
} catch (e) {
  // 地图注册失败（GeoJSON 异常）时降级为排行视图
  viewType.value = 'bar'
}

const totalSales = computed(() => regions.value.reduce((sum, item) => sum + (item.sales ?? 0), 0))
const topRegions = computed(() =>
  [...regions.value].sort((a, b) => (b.sales ?? 0) - (a.sales ?? 0)).slice(0, 10)
)
const maxSales = computed(() => topRegions.value[0]?.sales ?? 1)

function barWidth(item) {
  return Math.max(4, Math.round(((item.sales ?? 0) / maxSales.value) * 100))
}

/** 省份名标准化：广东省 / 内蒙古自治区 -> 广东 / 内蒙古（与 GeoJSON 名称对齐） */
function normalizeRegion(name) {
  if (!name) return ''
  return String(name)
    .replace(/(省|市|特别行政区)$/, '')
    .replace(/维吾尔自治区$/, '')
    .replace(/壮族自治区$/, '')
    .replace(/回族自治区$/, '')
    .replace(/自治区$/, '')
    .trim()
}

function buildMapOption() {
  const data = regions.value.map((item) => ({
    name: normalizeRegion(item.region),
    value: item.sales ?? 0,
    raw: item
  }))

  return {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        const raw = params.data?.raw
        if (!raw) return tooltipTitle(params.name) + tooltipRow('销售额', '暂无数据')
        return (
          tooltipTitle(raw.region) +
          tooltipRow('销售额', `¥${formatMoney(raw.sales)}`, COLORS.primary) +
          tooltipRow('订单量', formatNumber(raw.orderCount)) +
          tooltipRow('销售占比', formatPercent(raw.salesRatio))
        )
      }
    },
    visualMap: {
      min: 0,
      max: Math.max(...data.map((item) => item.value), 1),
      left: 8,
      bottom: 8,
      itemWidth: 10,
      itemHeight: 90,
      text: ['高', '低'],
      calculable: true,
      // 单色系热力：从极浅到主色，不用彩虹渐变
      inRange: { color: [withAlpha(COLORS.primary, 0.06), withAlpha(COLORS.primary, 0.45), COLORS.primary] },
      textStyle: { color: TEXT_SUB, fontSize: 11 },
      formatter: (value) => formatCompact(value)
    },
    series: [
      {
        type: 'map',
        map: MAP_NAME,
        roam: true,
        zoom: 1.16,
        label: { show: false },
        itemStyle: { borderColor: '#fdfcf8', borderWidth: 0.8, areaColor: '#f2f0e9' },
        emphasis: {
          label: { show: true, color: '#fff', fontSize: 11 },
          itemStyle: { areaColor: COLORS.primary, shadowBlur: 8, shadowColor: withAlpha(COLORS.primary, 0.4) }
        },
        select: { disabled: true },
        data
      }
    ]
  }
}

function buildBarOption() {
  const sorted = [...regions.value].sort((a, b) => (a.sales ?? 0) - (b.sales ?? 0)).slice(-12)
  const rowMap = new Map(sorted.map((item) => [item.region, item]))
  return {
    grid: { left: 4, right: 64, top: 12, bottom: 0, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(44,110,93,0.06)' } },
      formatter: (params) => {
        const row = rowMap.get(params[0]?.name) || {}
        return (
          tooltipTitle(row.region) +
          tooltipRow('销售额', `¥${formatMoney(row.sales)}`, COLORS.primary) +
          tooltipRow('订单量', formatNumber(row.orderCount)) +
          tooltipDivider +
          tooltipRow('销售占比', formatPercent(row.salesRatio))
        )
      }
    },
    xAxis: { type: 'value', axisLabel: { formatter: (value) => formatCompact(value) } },
    yAxis: {
      type: 'category',
      data: sorted.map((item) => item.region),
      axisLabel: { color: TEXT_SUB }
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 14,
        itemStyle: {
          borderRadius: [0, 6, 6, 0],
          color: horizontalGradient(COLORS.primary, 0.3, 1)
        },
        label: {
          show: true,
          position: 'right',
          color: TEXT_SUB,
          fontSize: 11,
          formatter: (params) => formatCompact(params.value)
        },
        data: sorted.map((item) => item.sales ?? 0)
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    const data = await getRegionSales(store.statParams)
    regions.value = safeArray(data?.items)
    subtitle.value = regions.value.length
      ? `覆盖 ${regions.value.length} 个省份 · 合计 ¥${formatMoney(totalSales.value)}`
      : ''

    if (regions.value.length === 0) {
      empty.value = true
      emptyText.value = '暂无地域数据（请确认订单数据包含 region 字段）'
      clear()
      return
    }

    // 省份名与地图完全不匹配时自动降级为排行视图
    const matched = regions.value.some((item) =>
      (chinaGeoJson.features || []).some(
        (feature) => feature.properties?.name === normalizeRegion(item.region)
      )
    )
    if (!matched && viewType.value === 'map') {
      viewType.value = 'bar'
      emptyText.value = '省份名称与地图不匹配，已切换为排行视图'
    }

    empty.value = false
    await renderChart(setOption, viewType.value === 'map' ? buildMapOption() : buildBarOption())
  } catch (e) {
    empty.value = true
    clear()
  } finally {
    loading.value = false
  }
}

// 视图切换只重绘，不重新请求
watch(viewType, () => {
  if (!empty.value) setOption(viewType.value === 'map' ? buildMapOption() : buildBarOption())
})
watch(() => store.revision, load)
onMounted(load)
</script>

<style lang="scss" scoped>
.region {
  display: flex;
  gap: 16px;
  align-items: stretch;
  /* 撑满卡片，与右侧订单表格卡片等高对齐 */
  height: 100%;

  @media (max-width: 1100px) {
    flex-direction: column;
    height: auto;
  }

  &__rank {
    flex: 0 0 200px;
    display: flex;
    flex-direction: column;
    min-width: 0;
    padding-left: 16px;
    border-left: 1px solid var(--bd-light);

    @media (max-width: 1100px) {
      flex: 1;
      padding-left: 0;
      border-left: none;
      border-top: 1px solid var(--bd-light);
      padding-top: 12px;
    }
  }

  &__rank-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 12px;
    color: var(--t-muted);
    margin-bottom: 10px;
  }

  &__rank-total {
    color: var(--t-main);
    font-weight: 600;
  }

  &__rank-list {
    list-style: none;
    margin: 0;
    padding: 0;
    /* 随卡片高度伸展，行数多时内部滚动 */
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  &__rank-item {
    display: grid;
    grid-template-columns: 18px 46px 1fr auto;
    align-items: center;
    gap: 8px;
    padding: 6px 0;
    font-size: 12px;
  }

  &__rank-no {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 5px;
    background: var(--bg-head);
    color: var(--t-muted);
    font-size: 11px;
    font-variant-numeric: tabular-nums;

    &.is-top {
      background: var(--c-primary-100);
      color: var(--c-primary);
      font-weight: 600;
    }
  }

  &__rank-name {
    color: var(--t-main);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__rank-bar {
    height: 6px;
    border-radius: 999px;
    background: var(--bg-head);
    overflow: hidden;

    i {
      display: block;
      height: 100%;
      border-radius: 999px;
      background: linear-gradient(90deg, rgba(44, 110, 93, 0.35), var(--c-primary));
    }
  }

  &__rank-value {
    color: var(--t-sub);
  }

  &__rank-empty {
    padding: 24px 0;
    text-align: center;
    color: var(--t-muted);
    font-size: 12px;
  }
}

.chart--region {
  flex: 1;
  min-width: 0;
  /* 高度由卡片决定（align-items: stretch），min-height 兜底防塌陷 */
  min-height: 260px;
}
</style>
