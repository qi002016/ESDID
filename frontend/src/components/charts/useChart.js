import echarts from './echarts'
import { nextTick, onBeforeUnmount, onMounted, shallowRef } from 'vue'
import { CHART_THEME_NAME, registerChartTheme } from './chartTheme'

// 注册全局图表主题（模块加载时执行一次）
registerChartTheme()

export {
  COLORS as CHART_COLORS,
  SERIES_COLORS,
  FONT_FAMILY,
  TEXT_COLOR,
  TEXT_SUB,
  TEXT_MUTED
} from './chartTheme'

/**
 * 判断接口数据是否为空（兼容 null / undefined / 空数组 / 空对象）
 * 所有图表统一用它决定是否显示空状态
 */
export function isEmptyData(data) {
  if (data === null || data === undefined) return true
  if (Array.isArray(data)) return data.length === 0
  if (typeof data === 'object') return Object.keys(data).length === 0
  return false
}

/**
 * ECharts 组合式封装
 * 统一处理：实例创建（套用全局主题）、容器尺寸自适应、卸载时 dispose 防内存泄漏
 * @param {import('vue').Ref<HTMLElement>} containerRef 图表容器 ref
 */
export function useChart(containerRef) {
  const chart = shallowRef(null)
  let resizeObserver = null

  const init = () => {
    if (!containerRef.value || chart.value) return chart.value
    chart.value = echarts.init(containerRef.value, CHART_THEME_NAME)
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => chart.value?.resize())
      resizeObserver.observe(containerRef.value)
    }
    return chart.value
  }

  /** 设置配置项（notMerge=true 避免切换数据时残留旧 series） */
  const setOption = (option, notMerge = true) => {
    const instance = init()
    if (!instance) return
    instance.setOption(option || {}, notMerge)
    instance.resize()
  }

  /** 清空图表（空数据时调用，避免残留上一次渲染） */
  const clear = () => {
    chart.value?.clear()
  }

  const resize = () => chart.value?.resize()

  onMounted(() => {
    requestAnimationFrame(() => init())
  })

  onBeforeUnmount(() => {
    resizeObserver?.disconnect()
    resizeObserver = null
    chart.value?.dispose()
    chart.value = null
  })

  return { chart, init, setOption, clear, resize, echarts }
}

/**
 * 渲染前等待 DOM 更新：
 * 空数据 -> 有数据切换时容器刚从 display:none 变可见，
 * 必须 nextTick 后再 init/setOption，否则 echarts 取到 0 宽高导致空白
 */
export async function renderChart(setOption, option) {
  await nextTick()
  setOption(option)
}
