<template>
  <!-- 单页看板应用：直接渲染看板首页；渲染异常时展示错误边界兜底 -->
  <DashboardIndex v-if="!renderError" />
  <div v-else class="app-error" role="alert">
    <h2>页面渲染出错</h2>
    <p>遇到未预期的错误，数据或界面可能未能正常加载。</p>
    <button type="button" class="app-error__btn" @click="reload">重新加载</button>
  </div>
</template>

<script setup>
import { onErrorCaptured, ref } from 'vue'
import DashboardIndex from '@/views/DashboardIndex.vue'

const renderError = ref(false)

/** Vue 错误边界：捕获子孙组件渲染错误，避免整页白屏 */
onErrorCaptured(() => {
  renderError.value = true
  // 阻止错误继续向上传播，避免控制台刷错
  return false
})

function reload() {
  renderError.value = false
  window.location.reload()
}
</script>

<style scoped>
.app-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 100vh;
  background: var(--bg-page, #f3f1ea);
  color: var(--t-main, #262a30);
  font-family: var(--font-sans, system-ui, sans-serif);
}

.app-error h2 {
  margin: 0;
  font-size: 20px;
}

.app-error p {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--t-muted, #8b929c);
}

.app-error__btn {
  padding: 8px 20px;
  border: none;
  border-radius: 8px;
  background: var(--c-primary, #2c6e5d);
  color: #fff;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
</style>
