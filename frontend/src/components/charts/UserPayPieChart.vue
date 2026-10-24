<template>
  <!--
    用户与支付方式分析：左侧环形图 + 右侧占比明细
    整行铺满时，居中环形图两侧会大片留白，因此改为「图 + 明细」横向分区，
    右侧列表同时承担原图例职责，信息密度更高
  -->
  <ChartCard
    title="用户与支付方式分析"
    :subtitle="subtitle"
    :loading="loading"
    :empty="empty"
    empty-height="300px"
  >
    <template #extra>
      <SegmentedControl v-model="dimension" :options="dimensionOptions" size="small" />
    </template>

    <div class="pie">
      <div ref="chartRef" class="chart chart--donut"></div>

      <div class="pie__side">
        <div class="pie__head">
          <span class="pie__head-label">{{ centerLabel }}</span>
          <b class="pie__head-value">{{ formatNumber(totalValue) }}</b>
        </div>

        <ul class="pie__list">
          <li v-for="item in chartItems" :key="item.name" class="pie__item">
            <i class="pie__dot" :style="{ background: item.color }"></i>
            <span class="pie__name" :title="item.name">{{ item.name }}</span>
            <span class="pie__track">
              <i :style="{ width: `${item.percent}%`, background: item.color }"></i>
            </span>
            <b class="pie__value">{{ formatNumber(item.value) }}</b>
            <span class="pie__percent">{{ item.percent.toFixed(1) }}%</span>
          </li>
        </ul>

        <div class="pie__stats">
          <div class="pie__stat">
            <span>复购率</span>
            <b>{{ formatPercent(analysis.repurchaseRate) }}</b>
          </div>
          <div class="pie__stat">
            <span>新用户占比</span>
            <b>{{ formatPercent(analysis.newUserRate) }}</b>
          </div>
        </div>
      </div>
    </div>
  </ChartCard>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import ChartCard from './ChartCard.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import { renderChart, useChart } from './useChart'
import { COLORS, FONT_FAMILY, TEXT_MUTED, tooltipRow, tooltipTitle } from './chartTheme'
import { useDashboardStore } from '@/store/dashboard'
import { getUserAnalysis } from '@/api'
import { formatMoney, formatNumber, formatPercent, safeArray, translatePayType } from '@/utils/format'

const store = useDashboardStore()
const chartRef = ref(null)
const loading = ref(false)
const empty = ref(true)
const subtitle = ref('')
const analysis = ref({})

const { setOption, clear } = useChart(chartRef)

/** 环形图维度切换：支付方式 / 用户类型 */
const dimension = ref('payType')
const dimensionOptions = [
  { label: '支付方式', value: 'payType' },
  { label: '用户类型', value: 'userType' }
]

const centerLabel = computed(() => (dimension.value === 'payType' ? '总支付笔数' : '总用户数'))

// 语义色分配：支付方式 4 类固定顺序（主色/紫/青/中性）
const palette = [COLORS.primary, COLORS.purple, COLORS.cyan, COLORS.slate]

/**
 * 环形图与右侧明细共用同一份数据源
 * 名称 / 数值 / 占比 / 配色一次算好，避免图与列表口径不一致
 */
const chartItems = computed(() => {
  const data = analysis.value || {}
  const isPay = dimension.value === 'payType'
  const items = isPay
    ? safeArray(data.payTypeRates).map((item) => ({
        name: translatePayType(item.payType),
        value: item.orderCount ?? 0,
        extra: item.rate
      }))
    : [
        { name: '新用户', value: data.newUserCount ?? 0, extra: data.newUserRate },
        { name: '老用户', value: data.oldUserCount ?? 0, extra: null }
      ]
  const total = items.reduce((sum, item) => sum + item.value, 0)
  return items.map((item, index) => ({
    ...item,
    color: palette[index % palette.length],
    percent: total ? (item.value / total) * 100 : 0
  }))
})

const totalValue = computed(() => chartItems.value.reduce((sum, item) => sum + item.value, 0))

function buildOption() {
  const items = chartItems.value
  const total = totalValue.value
  const isPay = dimension.value === 'payType'

  return {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        const row = params.data || {}
        const rate = row.extra !== null && row.extra !== undefined ? formatPercent(row.extra) : `${params.percent}%`
        return (
          tooltipTitle(params.name) +
          tooltipRow(isPay ? '支付笔数' : '用户数', formatNumber(params.value), params.color) +
          tooltipRow('占比', rate)
        )
      }
    },
    // 图例已由右侧明细列表承担，此处不再重复，环形图可占满整个画布
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
        style: { text: centerLabel.value, fontSize: 12, fill: TEXT_MUTED, fontFamily: FONT_FAMILY }
      }
    ],
    series: [
      {
        type: 'pie',
        // 画布变窄后放大环径，避免环形过小、四周再留白
        radius: ['54%', '76%'],
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        padAngle: 2,
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderColor: '#fdfcf8', borderWidth: 3, borderRadius: 4 },
        emphasis: { scale: true, scaleSize: 6 },
        data: items.map((item) => ({
          name: item.name,
          value: item.value,
          extra: item.extra,
          itemStyle: { color: item.color }
        }))
      }
    ]
  }
}

async function load() {
  loading.value = true
  try {
    const data = await getUserAnalysis(store.statParams)
    analysis.value = data || {}
    const payRates = safeArray(data?.payTypeRates)
    const userTotal = (data?.newUserCount ?? 0) + (data?.oldUserCount ?? 0)
    subtitle.value = data
      ? `复购率 ${formatPercent(data.repurchaseRate)} · 新用户占比 ${formatPercent(data.newUserRate)}`
      : ''
    if (payRates.length === 0 && userTotal === 0) {
      empty.value = true
      clear()
      return
    }
    empty.value = false
    await renderChart(setOption, buildOption())
  } catch (e) {
    empty.value = true
    clear()
  } finally {
    loading.value = false
  }
}

// 维度切换只需重绘，不必重新请求接口（A11 一次返回全部维度）
watch(dimension, () => {
  if (!empty.value) setOption(buildOption())
})
watch(() => store.revision, load)
onMounted(load)
</script>

<style lang="scss" scoped>
/* 左图右明细：整行铺满，两侧不再留白 */
.pie {
  display: grid;
  grid-template-columns: minmax(0, 300px) minmax(0, 1fr);
  gap: 16px 32px;
  align-items: center;
  height: 100%;

  @media (max-width: 1000px) {
    grid-template-columns: minmax(0, 1fr);
  }

  &__side {
    display: flex;
    flex-direction: column;
    gap: 10px;
    min-width: 0;
  }

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--bd-light);
  }

  &__head-label {
    font-size: 12px;
    color: var(--t-muted);
  }

  &__head-value {
    font-size: 18px;
    font-weight: 700;
    color: var(--t-main);
    font-variant-numeric: tabular-nums;
  }

  &__list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
  }

  /* 比例条占据弹性列，明细行可随卡片宽度自然伸展 */
  &__item {
    display: grid;
    grid-template-columns: 8px minmax(56px, 104px) minmax(60px, 1fr) auto 58px;
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

  &__stats {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 28px;
    padding-top: 10px;
    border-top: 1px solid var(--bd-light);
  }

  &__stat {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-size: 12px;
    color: var(--t-muted);

    b {
      font-size: 13px;
      color: var(--c-primary);
      font-variant-numeric: tabular-nums;
    }
  }
}

.chart--donut {
  width: 100%;
  height: 100%;
  min-height: 260px;
}

/* 窄屏堆叠后收窄并居中，避免整行拉出一个大而空的圆环 */
@media (max-width: 1000px) {
  .chart--donut {
    max-width: 320px;
    justify-self: center;
  }
}
</style>
