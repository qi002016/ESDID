<template>
  <!-- 迷你趋势图（sparkline）：无坐标轴，仅表达走势 -->
  <div ref="chartRef" class="spark" :style="{ height: `${height}px` }" :title="tip"></div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { renderChart, useChart } from './useChart'
import { COLORS, verticalGradient, withAlpha } from './chartTheme'

const props = defineProps({
  /** 数值序列；为空时绘制虚线基线（表示该指标无时间序列数据） */
  data: { type: Array, default: () => [] },
  color: { type: String, default: COLORS.primary },
  height: { type: Number, default: 40 },
  tip: { type: String, default: '' }
})

const chartRef = ref(null)
const { setOption } = useChart(chartRef)

function draw() {
  const values = Array.isArray(props.data) ? props.data.filter((v) => v !== null && v !== undefined) : []
  if (values.length < 2) {
    // 无序列：虚线基线 + 中性色，视觉上仍是一个完整的"占位趋势"
    setOption({
      animation: false,
      grid: { left: 0, right: 0, top: 4, bottom: 4 },
      xAxis: { type: 'category', show: false, boundaryGap: false, data: [0, 1] },
      yAxis: { type: 'value', show: false, min: 0, max: 1 },
      series: [
        {
          type: 'line',
          data: [0.5, 0.5],
          symbol: 'none',
          silent: true,
          lineStyle: { width: 1.5, type: 'dashed', color: withAlpha(COLORS.slate, 0.5) }
        }
      ]
    })
    return
  }
  setOption({
    animation: false,
    grid: { left: 0, right: 0, top: 4, bottom: 4 },
    xAxis: { type: 'category', show: false, boundaryGap: false, data: values.map((_, i) => i) },
    yAxis: { type: 'value', show: false, scale: true },
    series: [
      {
        type: 'line',
        data: values,
        smooth: true,
        symbol: 'none',
        silent: true,
        lineStyle: { width: 2, color: props.color },
        areaStyle: { color: verticalGradient(props.color, 0.22, 0.01) }
      }
    ]
  })
}

watch(() => [props.data, props.color], draw)
onMounted(draw)
</script>

<style lang="scss" scoped>
.spark {
  width: 100%;
  min-width: 60px;
}
</style>
