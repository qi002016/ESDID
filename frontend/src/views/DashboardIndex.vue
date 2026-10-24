<template>
  <!-- 后台大屏外壳：左侧深色导航 + 顶部导航 + 主内容区 -->
  <div class="shell">
    <AppSidebar @import="importVisible = true" />

    <div class="shell__main">
      <AppTopbar />

      <main class="shell__content">
        <!-- ============ 数据总览 ============ -->
        <template v-if="menu === 'overview'">
          <FilterBar />

          <!-- KPI 指标带 -->
          <DashboardKpiCard />

          <!--
            主干区双栏栅格：
            「RFM 用户分层」「促销活动效果分析」已从本页移除（仅保留在用户分析 / 营销活动子菜单），
            避免与子菜单重复；剩余四个核心模块按 7:5 / 5:7 交错分栏，
            既消除单列通栏的松散感，又通过宽度差制造视觉节奏。
          -->
          <!-- 第一行 7:5：销售趋势（主干趋势）+ 销售目标达成率 -->
          <div class="grid grid--7-5">
            <SalesTrendChart />
            <TargetGaugeChart />
          </div>

          <!-- 第二行 5:7：地域销售分布（近方形地图画布）+ 订单明细 -->
          <div class="grid grid--5-7">
            <RegionMapChart />
            <OrderTable compact @import="importVisible = true" />
          </div>

          <!-- 深度分析：默认折叠，压缩首屏高度；展开后按主题分组，边界清晰不糊成一片 -->
          <section class="deep">
            <button
              type="button"
              class="deep__head"
              :aria-expanded="deepOpen"
              aria-controls="deep-analysis-body"
              @click="toggleDeep"
            >
              <span class="deep__title">深度分析</span>
              <span class="deep__hint">用户结构 · 商品品类 · 金额与时段，共 5 个维度</span>
              <span class="deep__toggle">
                {{ deepOpen ? '收起' : '展开' }}
                <svg
                  class="deep__chevron"
                  :class="{ 'is-open': deepOpen }"
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="m6 9 6 6 6-6"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
            </button>

            <!-- v-if 首次展开才挂载（省掉首屏请求）；此后仅切换显隐，不重复取数 -->
            <div
              v-if="deepOpen || deepMounted"
              v-show="deepOpen"
              id="deep-analysis-body"
              class="deep__body"
            >
              <!--
                深度分析同样走栅格，避免折叠区里又出现 5 张通栏图：
                - 用户与支付方式分析是「环形图 + 右侧图例明细」的横向结构，本身就吃宽度，独占一行；
                - 商品 TOP10 与品类帕累托都是「少量类目 + 双轴」，1:1 并排最紧凑；
                - 客单价区间（6 档）与 24 小时分布（24 档）按 5:7，把宽度让给类目多的那张。
              -->
              <p class="deep__group">用户结构</p>
              <div class="grid grid--1">
                <UserPayPieChart />
              </div>

              <p class="deep__group">商品与品类结构</p>
              <div class="grid grid--2">
                <ProductTop10Chart />
                <CategoryParetoChart />
              </div>

              <p class="deep__group">金额与时段分布</p>
              <div class="grid grid--5-7">
                <OrderAmountDistChart />
                <HourlyDistChart />
              </div>
            </div>
          </section>
        </template>

        <!-- ============ 订单管理 ============ -->
        <template v-else-if="menu === 'orders'">
          <FilterBar />
          <div class="grid grid--1">
            <OrderTable @import="importVisible = true" />
          </div>
        </template>

        <!-- ============ 用户分析 ============ -->
        <template v-else-if="menu === 'users'">
          <FilterBar />
          <!-- 第一行 1:1：用户与支付方式分析 + RFM 用户分层 -->
          <div class="grid grid--2">
            <UserPayPieChart />
            <RfmSegmentChart />
          </div>
          <!-- 下方全宽：24 小时下单时段分布 -->
          <div class="grid grid--1">
            <HourlyDistChart />
          </div>
        </template>

        <!-- ============ 商品分析 ============ -->
        <template v-else-if="menu === 'products'">
          <FilterBar />
          <!-- 第一行 1:1：商品 TOP10 + 品类贡献分析 -->
          <div class="grid grid--2">
            <ProductTop10Chart />
            <CategoryParetoChart />
          </div>
          <!-- 下方全宽：客单价区间分布（底部的销售趋势已移除，与总览页重复） -->
          <div class="grid grid--1">
            <OrderAmountDistChart />
          </div>
        </template>

        <!-- ============ 营销活动 ============ -->
        <template v-else-if="menu === 'marketing'">
          <FilterBar />
          <!-- 第一行 1:1：促销活动效果分析 + 销售目标达成率 -->
          <div class="grid grid--2">
            <CampaignRoiChart />
            <TargetGaugeChart />
          </div>
          <div class="grid grid--1">
            <SalesTrendChart />
          </div>
        </template>

        <!-- ============ AI 智能问答（Text-to-SQL Agent）============ -->
        <AssistantView v-else-if="menu === 'assistant'" />
      </main>
    </div>

    <!-- A18 / A19 CSV 批量导入与任务轮询 -->
    <ImportModal v-model="importVisible" @finished="handleImportFinished" />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUiStore, MENU_LIST } from '@/store/ui'
import { useDashboardStore } from '@/store/dashboard'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import AppTopbar from '@/components/layout/AppTopbar.vue'
import FilterBar from '@/components/FilterBar.vue'
import DashboardKpiCard from '@/components/DashboardKpiCard.vue'
import OrderTable from '@/components/OrderTable.vue'
import ImportModal from '@/components/ImportModal.vue'
import SalesTrendChart from '@/components/charts/SalesTrendChart.vue'
import UserPayPieChart from '@/components/charts/UserPayPieChart.vue'
import RfmSegmentChart from '@/components/charts/RfmSegmentChart.vue'
import CampaignRoiChart from '@/components/charts/CampaignRoiChart.vue'
import RegionMapChart from '@/components/charts/RegionMapChart.vue'
import ProductTop10Chart from '@/components/charts/ProductTop10Chart.vue'
import CategoryParetoChart from '@/components/charts/CategoryParetoChart.vue'
import OrderAmountDistChart from '@/components/charts/OrderAmountDistChart.vue'
import HourlyDistChart from '@/components/charts/HourlyDistChart.vue'
import TargetGaugeChart from '@/components/charts/TargetGaugeChart.vue'
// AI 问答页按需加载：独立代码分块（含 echarts 与 agent 逻辑），不拖慢首屏
const AssistantView = defineAsyncComponent(() => import('./AssistantView.vue'))

const ui = useUiStore()
const store = useDashboardStore()
const importVisible = ref(false)

/** 菜单 key 必须存在于 MENU_LIST：失效的旧值（如已删除的系统设置）回退到总览 */
const menu = computed(() =>
  MENU_LIST.some((item) => item.key === ui.activeMenu) ? ui.activeMenu : 'overview'
)

/** 深度分析折叠态：默认收起，减少首屏滚动 */
const deepOpen = ref(false)
/** 是否已展开过：首次展开才挂载图表，之后只切显隐、不重复请求 */
const deepMounted = ref(false)

function toggleDeep() {
  deepOpen.value = !deepOpen.value
  if (!deepOpen.value) return
  deepMounted.value = true
  // 折叠期间图表容器尺寸为 0，展开后补一次全局 resize 兜底，避免图表空白
  nextTick(() => window.dispatchEvent(new Event('resize')))
}

/** 导入任务完成：刷新看板数据并推送通知 */
function handleImportFinished(report) {
  store.setFilters({})
  const valid = report?.validRows ?? 0
  const errors = report?.errorRows ?? 0
  if (valid > 0) {
    ElMessage.success(`导入完成：成功 ${valid} 行，失败 ${errors} 行`)
    ui.pushNotice(`CSV 导入完成：成功 ${valid} 行 / 失败 ${errors} 行`, errors > 0 ? 'warning' : 'success')
  }
}
</script>

<style lang="scss" scoped>
.shell {
  display: flex;
  height: 100%;
  overflow: hidden;

  &__main {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    height: 100%;
  }

  &__content {
    flex: 1;
    overflow-y: auto;
    padding: 20px 24px 40px;
    background: var(--bg-page);
  }
}

/* 卡片栅格：统一 16px 间距 */
.grid {
  display: grid;
  gap: var(--sp-gap);
  margin-bottom: var(--sp-gap);
  align-items: stretch;

  &--1 {
    grid-template-columns: minmax(0, 1fr);
  }

  &--2 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  &--2-1 {
    grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  }

  /* 7:5 —— 主干趋势（宽）+ 目标达成（窄），用宽度差制造视觉节奏 */
  &--7-5 {
    grid-template-columns: minmax(0, 7fr) minmax(0, 5fr);
  }

  /* 5:7 —— 地域分布（窄，近方形地图画布）+ 订单明细（宽） */
  &--5-7 {
    grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  }

  /* 三列：用于「时段 · 金额 · 目标达成」，避免仪表盘独占整行留白 */
  &--3 {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  /* 7:5 / 5:7 收得比 1:1 更早：窄侧卡片（目标达成、地图）本身是多栏布局，
     1600px 以下并排时内部栏宽不足，会退化成堆叠并把整行卡片拉到 700px+ 高，
     反而不如整行摆放整齐 */
  @media (max-width: 1600px) {
    &--7-5,
    &--5-7 {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  @media (max-width: 1180px) {
    &--2,
    &--2-1,
    &--3 {
      grid-template-columns: minmax(0, 1fr);
    }
  }
}

/* ===========================================================================
   深度分析面板：默认折叠 + 主题分组
   目的：首屏只保留核心指标与主干图表，分析维度按需展开，避免「一页到底」
   =========================================================================== */
.deep {
  margin-top: 4px;
  background: var(--bg-card);
  border: 1px solid var(--bd-light);
  border-radius: var(--r-card);
  box-shadow: var(--sh-card);
  overflow: hidden;

  &__head {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 11px 20px;
    border: none;
    background: var(--bg-head);
    font-family: inherit;
    text-align: left;
    cursor: pointer;
    transition: background 0.16s ease;

    &:hover {
      background: var(--bg-soft);
    }
  }

  &__title {
    font-size: 13px;
    font-weight: 600;
    color: var(--t-main);
    white-space: nowrap;
  }

  &__hint {
    font-size: 12px;
    color: var(--t-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    @media (max-width: 960px) {
      display: none;
    }
  }

  &__toggle {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-left: auto;
    font-size: 12px;
    color: var(--c-primary);
    white-space: nowrap;
  }

  &__chevron {
    width: 14px;
    height: 14px;
    transition: transform 0.2s ease;

    &.is-open {
      transform: rotate(180deg);
    }
  }

  /* 分隔线挂在 body 上：收起时 body 不渲染，不会留下悬空的双线 */
  &__body {
    padding: 16px 20px 20px;
    border-top: 1px solid var(--bd-light);

    .grid:last-child {
      margin-bottom: 0;
    }
  }

  /* 主题分组标题：小字 + 延伸细线，明确切分各分析主题 */
  &__group {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 0 10px;
    font-size: 12px;
    font-weight: 500;
    color: var(--t-sub);
    letter-spacing: 0.2px;

    &::after {
      content: '';
      flex: 1;
      height: 1px;
      background: var(--bd-light);
    }

    &:not(:first-child) {
      margin-top: 20px;
    }
  }
}

</style>
