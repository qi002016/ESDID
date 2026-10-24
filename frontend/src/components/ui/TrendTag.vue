<template>
  <!-- 涨跌标签：小色块 + 箭头，绿涨红跌 -->
  <span class="trend" :class="`trend--${direction}`">
    <span class="trend__arrow">{{ direction === 'up' ? '↑' : direction === 'down' ? '↓' : '—' }}</span>
    <span class="trend__value num">{{ text }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { toNumber } from '@/utils/format'

const props = defineProps({
  /** 增长率，0~1 小数（后端口径），null 表示无对比数据 */
  value: { type: [Number, String], default: null },
  /** 语义反转：如「流失率」上升是坏事时传 true */
  invert: { type: Boolean, default: false },
  suffix: { type: String, default: '同比' }
})

const direction = computed(() => {
  const num = toNumber(props.value)
  if (num === null) return 'flat'
  if (num === 0) return 'flat'
  const positive = num > 0
  const good = props.invert ? !positive : positive
  return good ? 'up' : 'down'
})

const text = computed(() => {
  const num = toNumber(props.value)
  if (num === null) return '无对比数据'
  const percent = `${num > 0 ? '+' : ''}${(num * 100).toFixed(1)}%`
  return `${percent} ${props.suffix}`
})
</script>

<style lang="scss" scoped>
.trend {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;

  &__arrow {
    font-size: 11px;
    line-height: 1;
  }

  &--up {
    color: var(--c-success);
    background: var(--c-success-50);
  }

  &--down {
    color: var(--c-danger);
    background: var(--c-danger-50);
  }

  &--flat {
    color: var(--t-muted);
    background: var(--bg-head);
  }
}
</style>
