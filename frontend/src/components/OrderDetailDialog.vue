<template>
  <!-- A6 订单详情弹窗：表格行点击后按订单号下钻查询 -->
  <el-dialog
    v-model="visible"
    title="订单详情"
    width="720px"
    :close-on-click-modal="true"
    @closed="handleClosed"
  >
    <div v-loading="loading" class="order-detail">
      <template v-if="detail">
        <el-descriptions :column="2" border size="default">
          <el-descriptions-item label="订单号" :span="2">
            <span class="order-detail__strong">{{ detail.orderId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="商品名称" :span="2">
            {{ detail.productName || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="商品品类">{{ detail.category || '-' }}</el-descriptions-item>
          <el-descriptions-item label="单价">
            ￥{{ formatMoney(detail.price) }}
          </el-descriptions-item>
          <el-descriptions-item label="购买数量">{{ detail.quantity ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="订单总金额">
            <span class="order-detail__amount">￥{{ formatMoney(detail.totalAmount) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="下单时间">
            {{ detail.orderTime || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="用户 ID">{{ detail.userId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="用户类型">
            <el-tag size="small" :type="detail.userType === 'new' ? 'success' : 'info'" effect="plain">
              {{ translateUserType(detail.userType) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="支付方式">
            {{ translatePayType(detail.payType) }}
          </el-descriptions-item>
          <el-descriptions-item label="是否促销单">
            <el-tag v-if="detail.promoFlag === 1" size="small" type="danger" effect="plain">
              促销单
            </el-tag>
            <span v-else>普通单</span>
          </el-descriptions-item>
          <el-descriptions-item label="收货省份">{{ detail.region || '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 金额核对：单价 × 数量 与后端返回总金额的一致性提示 -->
        <div class="order-detail__check">
          校验：单价 × 数量 =
          <b>￥{{ formatMoney((detail.price ?? 0) * (detail.quantity ?? 0)) }}</b>
          <el-tag v-if="amountMatched" size="small" type="success" effect="plain">与订单金额一致</el-tag>
          <el-tag v-else size="small" type="warning" effect="plain">与订单金额存在差异</el-tag>
        </div>
      </template>

      <!-- 订单不存在 / 未返回数据 -->
      <el-empty v-else-if="!loading" description="未获取到订单详情" :image-size="70" />
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getOrderDetail } from '@/api'
import { formatMoney, toNumber, translatePayType, translateUserType } from '@/utils/format'

const props = defineProps({
  // v-model 控制弹窗显隐
  modelValue: { type: Boolean, default: false },
  // 订单号（A2 表格行点击时传入）
  orderId: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const loading = ref(false)
const detail = ref(null)

/** 金额一致性校验（文档：容差 0.01） */
const amountMatched = computed(() => {
  if (!detail.value) return true
  const price = toNumber(detail.value.price) ?? 0
  const quantity = toNumber(detail.value.quantity) ?? 0
  const total = toNumber(detail.value.totalAmount) ?? 0
  return Math.abs(price * quantity - total) <= 0.01
})

/** 打开弹窗且带订单号时请求 A6 详情 */
async function load() {
  if (!props.orderId) return
  loading.value = true
  detail.value = null
  try {
    const data = await getOrderDetail(props.orderId)
    detail.value = data || null
  } catch (e) {
    // 订单不存在时后端返回 404，拦截器已统一提示
    detail.value = null
  } finally {
    loading.value = false
  }
}

function handleClosed() {
  detail.value = null
}

watch(() => [props.modelValue, props.orderId], ([show]) => {
  if (show) load()
})
</script>

<style lang="scss" scoped>
.order-detail {
  min-height: 120px;

  &__strong {
    font-weight: 600;
    color: var(--t-main);
  }

  &__amount {
    font-weight: 600;
    color: var(--c-primary);
  }

  &__check {
    margin-top: 12px;
    font-size: 13px;
    color: var(--t-muted);
    display: flex;
    align-items: center;
    gap: 8px;

    b {
      color: var(--t-main);
    }
  }
}
</style>
