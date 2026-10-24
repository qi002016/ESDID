<template>
  <!-- 全局筛选条：日期区间 + 品类(A1) / 支付方式 / 用户类型 / 促销标记，变更即刷新所有图表与表格 -->
  <div class="filter-bar">
    <span class="filter-bar__label">
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M3 6h18M7 12h10M10 18h4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
      </svg>
      筛选
    </span>

    <!-- 日期区间：自顶栏迁入，与其余筛选条件同处一行 -->
    <el-date-picker
      v-model="dateRange"
      type="daterange"
      unlink-panels
      range-separator="→"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
      value-format="YYYY-MM-DD"
      :shortcuts="shortcuts"
      :clearable="false"
      class="filter-bar__date"
    />

    <el-select
      v-model="form.category"
      placeholder="全部品类"
      clearable
      class="filter-bar__select"
      :loading="categoryLoading"
    >
      <el-option
        v-for="item in categories"
        :key="item.category"
        :label="`${item.category}（${item.productCount ?? 0}）`"
        :value="item.category"
      />
    </el-select>

    <el-select v-model="form.payType" placeholder="支付方式" clearable class="filter-bar__select">
      <el-option
        v-for="item in PAY_TYPE_OPTIONS"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>

    <el-select v-model="form.userType" placeholder="用户类型" clearable class="filter-bar__select">
      <el-option
        v-for="item in USER_TYPE_OPTIONS"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>

    <el-select v-model="form.promoFlag" placeholder="促销标记" clearable class="filter-bar__select">
      <el-option
        v-for="item in PROMO_FLAG_OPTIONS"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>

    <el-button text class="filter-bar__reset" @click="handleReset">重置</el-button>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useDashboardStore } from '@/store/dashboard'
import { getCategories } from '@/api'
import {
  PAY_TYPE_OPTIONS,
  USER_TYPE_OPTIONS,
  PROMO_FLAG_OPTIONS,
  recentRange,
  safeArray
} from '@/utils/format'

const store = useDashboardStore()
const categories = ref([])
const categoryLoading = ref(false)

const form = reactive({
  category: store.category,
  payType: store.payType,
  userType: store.userType,
  promoFlag: store.promoFlag
})

/** 日期区间（自顶栏迁入）：与下拉筛选同处一条，变更即全量刷新 */
const dateRange = ref([store.startDate, store.endDate])

/** 快捷区间：近 7 / 30 / 90 天 */
const shortcuts = [
  { text: '近 7 天', value: () => toDates(recentRange(7)) },
  { text: '近 30 天', value: () => toDates(recentRange(30)) },
  { text: '近 90 天', value: () => toDates(recentRange(90)) }
]

function toDates([start, end]) {
  return [new Date(`${start}T00:00:00`), new Date(`${end}T00:00:00`)]
}

/** 下拉变更即时写入全局筛选（revision +1 → 全量刷新） */
function apply() {
  store.setFilters({ ...form })
}

function handleReset() {
  store.resetFilters()
  form.category = ''
  form.payType = ''
  form.userType = ''
  form.promoFlag = ''
}

/** A1 品类列表 */
async function loadCategories() {
  categoryLoading.value = true
  try {
    categories.value = safeArray(await getCategories())
  } catch (e) {
    categories.value = []
  } finally {
    categoryLoading.value = false
  }
}

watch(() => [form.category, form.payType, form.userType, form.promoFlag], apply)

/** 日期变更即写入全局筛选，触发所有图表与表格刷新 */
watch(dateRange, (value) => {
  if (value?.[0] && value?.[1]) {
    store.setFilters({ startDate: value[0], endDate: value[1] })
  }
})

// 重置 / 外部变更后回填
watch(
  () => [store.category, store.payType, store.userType, store.promoFlag],
  ([category, payType, userType, promoFlag]) => {
    if (form.category !== category) form.category = category
    if (form.payType !== payType) form.payType = payType
    if (form.userType !== userType) form.userType = userType
    if (form.promoFlag !== promoFlag) form.promoFlag = promoFlag
  }
)

// 重置 / 外部变更后回填日期
watch(
  () => [store.startDate, store.endDate],
  ([start, end]) => {
    if (dateRange.value?.[0] !== start || dateRange.value?.[1] !== end) {
      dateRange.value = [start, end]
    }
  }
)

onMounted(loadCategories)
</script>

<style lang="scss" scoped>
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 20px;
  background: var(--bg-card);
  border: 1px solid var(--bd-light);
  border-radius: var(--r-card);
  box-shadow: var(--sh-card);

  &__label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12.5px;
    color: var(--t-muted);
    margin-right: 2px;

    svg {
      width: 14px;
      height: 14px;
    }
  }

  /*
    日期区间：Element Plus 的 .el-date-editor--daterange 自带 width:350px（CSS 变量）
    且作为 flex 项会被拉伸到整行剩余宽度（1920 下实测 885px，连中间的 → 都被拉到 162px）。

    注意：class 实际落在 el-tooltip__trigger 那一层内部节点上，拿不到 Vue 的
    作用域属性（data-v-xxx），所以必须用 :deep() 穿透，否则规则完全不生效。
  */
  :deep(.filter-bar__date) {
    flex: 0 0 auto;
    width: 226px;

    .el-range-input {
      font-size: 12.5px;
      font-variant-numeric: tabular-nums;
    }

    /* 分隔符不再参与拉伸，收成一个小箭头 */
    .el-range-separator {
      flex: 0 0 auto;
      width: auto;
      padding: 0 2px;
      color: var(--t-muted);
    }

    .el-range__icon {
      margin-right: 4px;
      color: var(--t-muted);
    }
  }

  &__select {
    /*
      日期框收回固定宽度后，右侧会空出一截；让下拉框轻微伸展补回来，
      同时设上限避免被拉成横条。
      不设 min-width：交给 flex 的 min-content 兜底，窄屏时先自然收窄，
      实在放不下才换行，避免过早折成两行。
    */
    flex: 1 1 138px;
    max-width: 240px;
  }

  &__reset {
    /*
      宽屏：靠右对齐，与筛选控件拉开距离（此时整行几乎占满，间隙不明显）。
      中窄屏：控件已占满整行、甚至换行，再靠右会在行尾留出大片空档，因此改为紧随其后。
    */
    margin-left: auto;
    color: var(--t-sub);

    @media (max-width: 1660px) {
      margin-left: 0;
    }
  }
}
</style>
