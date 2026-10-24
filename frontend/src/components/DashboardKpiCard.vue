<template>
  <!--
    KPI 指标带（对应需求文档二 F1）
    六项指标：总销售额 / 总订单数 / 平均客单价 / 热销商品数 / 活跃用户 / 复购率
    布局：单张纸面 + 1px 发丝线自动分栏（gap 充当分隔线，任意换行都保持整齐）
  -->
  <div v-loading="loading" class="kpi">
    <div v-for="item in metrics" :key="item.key" class="kpi__card">
      <div class="kpi__top">
        <span class="kpi__label">{{ item.label }}</span>
        <TrendTag :value="item.growth" />
      </div>

      <div class="kpi__main">
        <div class="kpi__value num">
          <span v-if="item.prefix" class="kpi__prefix">{{ item.prefix }}</span>
          <span class="kpi__number">{{ item.value }}</span>
        </div>

        <!-- 有日粒度序列的指标画迷你趋势；没有的用一行「统计口径」占位，避免出现空槽 -->
        <SparklineChart
          v-if="item.spark && item.spark.length"
          class="kpi__spark"
          :data="item.spark"
          :color="item.color"
          :tip="item.sparkTip"
        />
        <span v-else class="kpi__caliber">{{ item.caliber }}</span>
      </div>

      <div class="kpi__foot">
        <span class="kpi__foot-label">上期</span>
        <span class="num">{{ item.previousText }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import TrendTag from '@/components/ui/TrendTag.vue'
import SparklineChart from '@/components/charts/SparklineChart.vue'
import { useDashboardStore } from '@/store/dashboard'
import { getOrdersKpi, getOrdersStats } from '@/api'
import { COLORS } from '@/components/charts/chartTheme'
import {
  formatMoney,
  formatNumber,
  formatPercent,
  growthRate,
  previousRange,
  safeArray
} from '@/utils/format'

const store = useDashboardStore()
const loading = ref(false)
const kpi = ref({}) // 本期 A3
const prevKpi = ref({}) // 上期 A3（用于环比）
const dailySales = ref([]) // A5 日粒度：销售额序列
const dailyOrders = ref([]) // A5 日粒度：订单量序列

/**
 * 六项指标（前三项对应文档 F1 的「总销售额 / 总订单数 / 平均客单价」，
 * 第四项对应 F1 的「热销商品数」；活跃用户、复购率是 A3 提供的增强指标）
 *
 * 迷你趋势的数据来源：
 *  - 总销售额 / 总订单数：直接取 A5 日粒度序列；
 *  - 平均客单价：由 A5 的「当日销售额 ÷ 当日订单量」派生；
 *  - 其余三项接口未提供时间序列，右侧改展示统计口径，不做无数据支撑的图形。
 */
const metrics = computed(() => {
  const sales = dailySales.value
  const orders = dailyOrders.value
  const avgSeries = sales
    .map((value, index) => (orders[index] ? Number((value / orders[index]).toFixed(2)) : null))
    .filter((value) => value !== null)

  return [
    {
      key: 'sales',
      label: '总销售额',
      prefix: '¥',
      value: formatMoney(kpi.value.totalSales),
      previousText: `¥${formatMoney(prevKpi.value.totalSales)}`,
      growth: growthRate(kpi.value.totalSales, prevKpi.value.totalSales),
      spark: sales,
      color: COLORS.primary,
      sparkTip: '区间内每日销售额走势'
    },
    {
      key: 'orders',
      label: '总订单数',
      value: formatNumber(kpi.value.totalOrders),
      previousText: formatNumber(prevKpi.value.totalOrders),
      growth: growthRate(kpi.value.totalOrders, prevKpi.value.totalOrders),
      spark: orders,
      color: COLORS.purple,
      sparkTip: '区间内每日订单量走势'
    },
    {
      key: 'avgOrderValue',
      label: '平均客单价',
      prefix: '¥',
      value: formatMoney(kpi.value.avgOrderValue),
      previousText: `¥${formatMoney(prevKpi.value.avgOrderValue)}`,
      growth: growthRate(kpi.value.avgOrderValue, prevKpi.value.avgOrderValue),
      spark: avgSeries,
      color: COLORS.cyan,
      sparkTip: '区间内每日客单价走势（当日销售额 ÷ 当日订单量）'
    },
    {
      key: 'hotProducts',
      label: '热销商品数',
      value: formatNumber(kpi.value.hotProductCount),
      previousText: formatNumber(prevKpi.value.hotProductCount),
      growth: growthRate(kpi.value.hotProductCount, prevKpi.value.hotProductCount),
      spark: [],
      color: COLORS.warning,
      caliber: '成交商品去重数'
    },
    {
      key: 'users',
      label: '活跃用户',
      value: formatNumber(kpi.value.totalUsers),
      previousText: formatNumber(prevKpi.value.totalUsers),
      growth: growthRate(kpi.value.totalUsers, prevKpi.value.totalUsers),
      spark: [],
      color: COLORS.success,
      caliber: '下单用户去重数'
    },
    {
      key: 'repurchase',
      label: '复购率',
      value: formatPercent(kpi.value.repurchaseRate),
      previousText: formatPercent(prevKpi.value.repurchaseRate),
      growth: growthRate(kpi.value.repurchaseRate, prevKpi.value.repurchaseRate),
      spark: [],
      color: COLORS.primary,
      caliber: '下单 ≥2 次占比'
    }
  ]
})

async function load() {
  loading.value = true
  try {
    const [prevStart, prevEnd] = previousRange(store.startDate, store.endDate)
    // 并行取：本期 KPI（A3）、上期 KPI（A3）、日粒度序列（A5，用于 sparkline）
    const [current, previous, stats] = await Promise.all([
      getOrdersKpi(store.statParams),
      getOrdersKpi({ ...store.statParams, startDate: prevStart, endDate: prevEnd }),
      getOrdersStats({ ...store.statParams, granularity: 'day' })
    ])
    kpi.value = current || {}
    prevKpi.value = previous || {}
    const items = safeArray(stats?.items)
    dailySales.value = items.map((item) => item.sales ?? 0)
    dailyOrders.value = items.map((item) => item.orders ?? 0)
  } catch (e) {
    // 拦截器已统一提示，这里清空避免展示旧值
    kpi.value = {}
    prevKpi.value = {}
    dailySales.value = []
    dailyOrders.value = []
  } finally {
    loading.value = false
  }
}

watch(() => store.revision, load)
onMounted(load)
</script>

<style lang="scss" scoped>
/*
  经营指标带：单张纸面 + 发丝线分栏。
  分栏线由 1px 的 grid gap 露出底色形成，因此无论排成 6 / 3 / 2 列，
  横竖分隔线都自动对齐，不需要为每个卡片单独算 border。
*/
.kpi {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 1px;
  background: var(--bd-light);
  border: 1px solid var(--bd-light);
  border-radius: var(--r-card);
  box-shadow: var(--sh-card);
  overflow: hidden;

  /* 中屏退化为 3 列 2 行，数字获得足够宽度 */
  @media (max-width: 1660px) {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  /* 窄屏 2 列 3 行 */
  @media (max-width: 1020px) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  /* 极窄屏单列，避免长数字被卡片宽度挤爆 */
  @media (max-width: 760px) {
    grid-template-columns: minmax(0, 1fr);
  }

  /*
    卡片内部统一为「固定三层」结构：
      顶部 20px  —— 标题 + 同比标签（justify-between）
      中部 44px  —— 主数字 + 右侧迷你图 / 统计口径（baseline 对齐 + justify-between）
      底部 25px  —— 上期对比数据
    三层都给定高，6 张卡片里同一层的元素必然落在同一条水平线上；
    否则「有迷你图的卡」和「只有口径文字的卡」会因内容高度不同而整体错位。
  */
  &__card {
    display: flex;
    flex-direction: column;
    min-width: 0;
    padding: 12px 18px 11px;
    background: var(--bg-card);
  }

  &__top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    height: 20px;
    flex: 0 0 auto;
  }

  &__label {
    font-size: 12.5px;
    color: var(--t-sub);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__main {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
    height: 44px;
    flex: 0 0 auto;
  }

  &__value {
    display: flex;
    align-items: baseline;
    min-width: 0;
    /* 行高撑满中部高度：数字在 44px 槽位内垂直居中，
       同时基线位置稳定，6 张卡片的数字底部落在同一条基线上 */
    line-height: 44px;
    font-size: 20px;
    font-weight: 700;
    color: var(--t-main);
    letter-spacing: -0.5px;
    white-space: nowrap;

    /* 列数变少 -> 卡片变宽 -> 数字放大，保证视觉重量 */
    @media (max-width: 1660px) {
      font-size: 25px;
    }

    @media (max-width: 520px) {
      font-size: 20px;
    }
  }

  &__prefix {
    font-size: 14px;
    font-weight: 600;
    color: var(--t-sub);
    margin-right: 2px;
  }

  &__spark {
    /* 迷你图是图形，基线对齐会让它整体上飘，这里单独垂直居中 */
    align-self: center;
    flex: 1 1 0;
    min-width: 0;
    max-width: 180px;
    opacity: 0.95;
  }

  /* 无日粒度序列的指标：右侧标注统计口径，与主数字共用同一条基线 */
  &__caliber {
    flex: 0 1 auto;
    max-width: 130px;
    text-align: right;
    font-size: 11px;
    line-height: 44px;
    color: var(--t-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;

    /* 极窄屏把宽度让给数字 */
    @media (max-width: 560px) {
      display: none;
    }
  }

  &__foot {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 25px;
    flex: 0 0 auto;
    margin-top: 7px;
    padding-top: 6px;
    border-top: 1px solid var(--bd-light);
    font-size: 11.5px;
    color: var(--t-muted);

    .num {
      color: var(--t-sub);
    }
  }

  &__foot-label {
    color: var(--t-muted);
  }
}

/* 迷你趋势图自身的 min-width 会顶住 6 列窄卡，这里放开让它跟随 flex 收缩 */
.kpi :deep(.spark) {
  min-width: 0;
}
</style>
