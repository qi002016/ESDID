<template>
  <!-- 通用卡片容器：白色圆角 12px、轻阴影、内边距 24px -->
  <section class="card">
    <header v-if="title || $slots.extra" class="card__head">
      <div class="card__title-wrap">
        <h3 class="card__title">{{ title }}</h3>
        <span v-if="subtitle" class="card__subtitle" :title="subtitle">{{ subtitle }}</span>
      </div>
      <div class="card__extra">
        <slot name="extra" />
      </div>
    </header>

    <div v-loading="loading" element-loading-text="加载中" class="card__body">
      <!-- 图表容器始终挂载（v-show），保证 echarts.init 能取到真实宽高 -->
      <div v-show="!empty" class="card__content">
        <slot />
      </div>
      <!-- 空数据：虚线占位框 + 居中提示（不使用纸箱插画） -->
      <div v-if="empty && !loading" class="card__empty" :style="{ minHeight: emptyHeight }">
        <span>{{ emptyText }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
/**
 * 卡片 + loading + 空状态容器
 * 各图表组件只负责取数与 setOption，视觉状态由本组件统一呈现
 */
defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  empty: { type: Boolean, default: true },
  emptyText: { type: String, default: '暂无数据' },
  /** 空状态占位框最小高度，与图表容器高度保持一致避免布局跳动 */
  emptyHeight: { type: String, default: '260px' }
})
</script>

<style lang="scss" scoped>
.card {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: var(--sp-card);
  background: var(--bg-card);
  border: 1px solid var(--bd-light);
  border-radius: var(--r-card);
  box-shadow: var(--sh-card);
  transition: box-shadow 0.2s ease;

  &:hover {
    box-shadow: var(--sh-hover);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 20px;
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
  }

  &__extra {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    min-height: 0;
  }

  /* 撑满 body：栅格等高时图表可随卡片伸展，避免卡片底部留白造成参差 */
  &__content {
    width: 100%;
    height: 100%;
  }

  /* 虚线占位框：干净、克制 */
  &__empty {
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px dashed var(--bd-base);
    border-radius: var(--r-control);
    background: linear-gradient(180deg, rgba(247, 245, 239, 0.6), rgba(253, 252, 248, 0));
    color: var(--t-muted);
    font-size: 13px;
    letter-spacing: 0.2px;
  }
}
</style>
