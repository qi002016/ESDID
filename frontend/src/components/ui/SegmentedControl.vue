<template>
  <!-- 小圆角分段控件：选中态主色填充 -->
  <div class="segmented" :class="{ 'segmented--sm': size === 'small' }">
    <button
      v-for="item in options"
      :key="item.value"
      type="button"
      class="segmented__item"
      :class="{ 'is-active': item.value === modelValue }"
      @click="emit('update:modelValue', item.value)"
    >
      {{ item.label }}
    </button>
  </div>
</template>

<script setup>
/**
 * 分段控件（替代 el-radio-group 的按钮样式）
 * 用于卡片右上角的「柱状图 / 环形图」「地图 / 排行」等切换
 */
defineProps({
  modelValue: { type: [String, Number], default: '' },
  // [{ label: '柱状图', value: 'bar' }]
  options: { type: Array, default: () => [] },
  size: { type: String, default: 'default' }
})
const emit = defineEmits(['update:modelValue'])
</script>

<style lang="scss" scoped>
.segmented {
  display: inline-flex;
  padding: 2px;
  background: var(--bg-head);
  border: 1px solid var(--bd-light);
  border-radius: var(--r-control);

  &__item {
    padding: 0 12px;
    height: 26px;
    border: none;
    background: transparent;
    border-radius: 6px;
    font-size: 12px;
    font-family: inherit;
    color: var(--t-sub);
    cursor: pointer;
    transition: all 0.16s ease;
    white-space: nowrap;

    &:hover {
      color: var(--t-main);
    }

    &.is-active {
      background: var(--c-primary);
      color: #fff;
      box-shadow: 0 1px 3px rgba(44, 110, 93, 0.35);
    }
  }

  &--sm &__item {
    height: 22px;
    padding: 0 10px;
    font-size: 11px;
  }
}
</style>
