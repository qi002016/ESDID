<template>
  <!-- 订单明细：分页表格 + 行点击下钻(A6) + 导出(A12) + 导入(A18) -->
  <section class="order-card">
    <header class="order-card__head">
      <div class="order-card__title-wrap">
        <h3 class="order-card__title">订单明细</h3>
        <span class="order-card__subtitle">
          共 <b class="num">{{ formatNumber(total) }}</b> 条 · {{ store.filterSummary }}
        </span>
      </div>
      <div class="order-card__actions">
        <el-button size="small" @click="handleRefresh">
          <svg class="order-card__btn-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M20 11a8 8 0 1 0-2.3 5.7M20 11V5m0 6h-6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          刷新
        </el-button>
        <el-button size="small" @click="emit('import')">导入</el-button>
        <el-button size="small" type="primary" :loading="exporting" @click="handleExport">
          <svg class="order-card__btn-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 4v12m0 0-4-4m4 4 4-4M4 18v1a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          导出 CSV
        </el-button>
      </div>
    </header>

    <el-table
      v-loading="loading"
      v-bind="tableSizeProp"
      :data="records"
      size="small"
      stripe
      border
      highlight-current-row
      empty-text="暂无订单数据"
      @row-click="handleRowClick"
      @sort-change="handleSortChange"
    >
      <el-table-column prop="orderId" label="订单号" width="150" fixed show-overflow-tooltip />
      <el-table-column prop="productName" label="商品名" min-width="140" show-overflow-tooltip />
      <el-table-column prop="category" label="品类" width="86" />
      <template v-if="!compact">
        <el-table-column prop="price" label="单价" width="102" align="right">
          <template #default="{ row }">
            <span class="num">¥{{ formatMoney(row.price) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="72" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.quantity ?? 0 }}</span>
          </template>
        </el-table-column>
      </template>
      <el-table-column prop="totalAmount" label="订单金额" width="124" align="right" sortable="custom">
        <template #default="{ row }">
          <span class="num order-card__amount">¥{{ formatMoney(row.totalAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="orderTime" label="下单时间" width="164" sortable="custom">
        <template #default="{ row }">
          <span class="num">{{ row.orderTime }}</span>
        </template>
      </el-table-column>
      <template v-if="!compact">
        <el-table-column prop="userId" label="用户 ID" width="94">
          <template #default="{ row }">
            <span class="num">{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="userType" label="用户类型" width="94" align="center">
          <template #default="{ row }">
            <span class="order-card__tag" :class="row.userType === 'new' ? 'is-new' : 'is-old'">
              {{ translateUserType(row.userType) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="payType" label="支付方式" width="98" align="center">
          <template #default="{ row }">{{ translatePayType(row.payType) }}</template>
        </el-table-column>
        <el-table-column prop="promoFlag" label="促销" width="76" align="center">
          <template #default="{ row }">
            <span v-if="row.promoFlag === 1" class="order-card__tag is-promo">促销</span>
            <span v-else class="order-card__muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="region" label="省份" width="86" />
      </template>
      <el-table-column label="操作" width="72" fixed="right" align="center">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click.stop="handleRowClick(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="order-card__pager">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        :layout="compact ? 'prev, pager, next' : 'total, sizes, prev, pager, next, jumper'"
        :pager-count="compact ? 5 : 7"
        background
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <!-- A6 订单详情弹窗 -->
    <OrderDetailDialog v-model="detailVisible" :order-id="currentOrderId" />
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import OrderDetailDialog from './OrderDetailDialog.vue'
import { useDashboardStore } from '@/store/dashboard'
import { useUiStore } from '@/store/ui'
import { exportOrdersCsv, getOrderPage } from '@/api'
import { downloadBlob, resolveFilename, buildCsvFilename } from '@/utils/download'
import {
  formatMoney,
  formatNumber,
  safeArray,
  translatePayType,
  translateUserType
} from '@/utils/format'

const props = defineProps({
  /** 紧凑模式：用于总览页半宽卡片，减少列并精简分页器 */
  compact: { type: Boolean, default: false }
})
const emit = defineEmits(['import'])

const store = useDashboardStore()
const ui = useUiStore()
const records = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)

const detailVisible = ref(false)
const currentOrderId = ref('')

/**
 * 表格尺寸：
 * - 总览页 compact：固定 height，与同排的地域地图卡片强制等高
 * - 订单管理页：改用 max-height，数据不足一屏时表格随内容自然收缩，
 *   卡片底部直接紧贴分页组件，不再出现固定高度留下的整片空白
 */
const tableSizeProp = computed(() => (props.compact ? { height: 340 } : { maxHeight: 560 }))

const currentPage = computed({
  get: () => store.orderPage,
  set: (value) => store.setOrderQuery({ orderPage: value })
})
const pageSize = computed({
  get: () => store.orderSize,
  set: (value) => store.setOrderQuery({ orderSize: value })
})

/** A2 分页查询：参数全部来自 store（page/size/category/keyword/payType/userType/promoFlag/startDate/endDate/sort） */
async function load() {
  loading.value = true
  try {
    const data = await getOrderPage(store.orderParams)
    records.value = safeArray(data?.records)
    total.value = data?.total ?? 0
    // 当前页被筛空时回退第一页（由 watch 自动重新请求）
    if (records.value.length === 0 && total.value > 0 && store.orderPage > 1) {
      store.setOrderQuery({ orderPage: 1 })
    }
  } catch (e) {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleRefresh() {
  load()
  ui.pushNotice('订单明细已刷新', 'info')
}

function handlePageChange(page) {
  store.setOrderQuery({ orderPage: page })
}

function handleSizeChange(size) {
  store.setOrderQuery({ orderSize: size, orderPage: 1 })
}

/** 排序：sort 参数格式 "字段,asc|desc"（文档默认 orderTime,desc） */
function handleSortChange({ prop, order }) {
  if (!prop || !order) {
    store.setOrderQuery({ orderSort: 'orderTime,desc' })
    return
  }
  store.setOrderQuery({ orderSort: `${prop},${order === 'ascending' ? 'asc' : 'desc'}` })
}

/** 行点击 -> A6 详情下钻 */
function handleRowClick(row) {
  if (!row?.orderId) return
  currentOrderId.value = row.orderId
  detailVisible.value = true
}

/** A12 导出当前筛选条件下的订单明细 */
async function handleExport() {
  exporting.value = true
  try {
    const response = await exportOrdersCsv(store.exportParams)
    const filename =
      resolveFilename(response, null) || buildCsvFilename(store.startDate, store.endDate)
    downloadBlob(response.data, filename)
    ElMessage.success('导出成功')
    ui.pushNotice(`已导出订单明细：${filename}`, 'success')
  } catch (e) {
    // 拦截器已统一提示
  } finally {
    exporting.value = false
  }
}

// 筛选变更（revision）或分页/排序变化都会改变 orderParams -> 重新请求
watch(() => [store.orderParams, store.revision], load, { deep: true })
onMounted(load)
</script>

<style lang="scss" scoped>
.order-card {
  display: flex;
  flex-direction: column;
  /* 高度由内容决定：不再用 height:100% 撑满栅格行，
     避免表格下方（表格与分页之间）出现大片空白 */
  height: auto;
  padding: var(--sp-card);
  background: var(--bg-card);
  /* 与 ChartCard 保持同一描边，避免同排卡片视觉轻重不一 */
  border: 1px solid var(--bd-light);
  border-radius: var(--r-card);
  box-shadow: var(--sh-card);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 18px;
  }

  &__title-wrap {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-width: 0;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: var(--t-main);
    white-space: nowrap;
  }

  &__subtitle {
    font-size: 12px;
    color: var(--t-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    b {
      color: var(--t-sub);
      font-weight: 600;
    }
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
  }

  &__btn-icon {
    width: 14px;
    height: 14px;
    margin-right: 4px;
    vertical-align: -2px;
  }

  &__amount {
    font-weight: 600;
    color: var(--t-main);
  }

  &__tag {
    display: inline-block;
    padding: 1px 7px;
    border-radius: var(--r-pill);
    font-size: 11px;
    line-height: 18px;

    &.is-new {
      color: var(--c-primary);
      background: var(--c-primary-50);
    }

    &.is-old {
      color: var(--t-sub);
      background: var(--bg-head);
    }

    &.is-promo {
      color: var(--c-warning);
      background: var(--c-warning-50);
    }
  }

  &__muted {
    color: var(--t-muted);
  }

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  /* 表格圆角与留白（Linear/AntD 观感） */
  :deep(.el-table) {
    border-radius: var(--r-control);
    overflow: hidden;
  }

  :deep(.el-table th.el-table__cell) {
    font-size: 12.5px;
  }

  :deep(.el-table .cell) {
    line-height: 22px;
  }
}
</style>
