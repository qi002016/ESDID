import echarts from './echarts'

/**
 * 图表主题（全局注册一次，useChart 初始化时套用）
 * 目的：让所有图表天然共享同一套文字色、坐标轴、tooltip、legend 样式，
 *      避免每个图表组件重复堆样式，也保证视觉绝对一致。
 */

/** 语义化配色（矿物颜料）：按业务含义分配，不用默认彩虹色 */
export const COLORS = {
  primary: '#2c6e5d',
  purple: '#7c5fa3',
  cyan: '#3e8b8f',
  success: '#4c956c',
  warning: '#b97e1f',
  danger: '#c23b2e',
  slate: '#93a0a8'
}

/** 图表系列默认取色顺序（语义优先） */
export const SERIES_COLORS = [
  COLORS.primary,
  COLORS.purple,
  COLORS.cyan,
  COLORS.success,
  COLORS.warning,
  COLORS.danger,
  COLORS.slate
]

export const FONT_FAMILY =
  "'Inter', 'Source Han Sans SC', 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"

export const TEXT_COLOR = '#262a30'
export const TEXT_SUB = '#5f6670'
export const TEXT_MUTED = '#8b929c'
export const BORDER_COLOR = '#e2dfd5'
export const SPLIT_COLOR = '#ece9e0'

const THEME_NAME = 'bi'

/** 注册主题（幂等：模块只加载一次） */
export function registerChartTheme() {
  echarts.registerTheme(THEME_NAME, {
    color: SERIES_COLORS,
    textStyle: { fontFamily: FONT_FAMILY, color: TEXT_COLOR },
    title: { textStyle: { color: TEXT_COLOR, fontWeight: 600, fontSize: 13 } },
    legend: {
      icon: 'roundRect',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 16,
      textStyle: { color: TEXT_SUB, fontSize: 12, fontFamily: FONT_FAMILY }
    },
    tooltip: {
      backgroundColor: 'rgba(253,252,248,0.98)',
      borderColor: BORDER_COLOR,
      borderWidth: 1,
      padding: [10, 12],
      textStyle: { color: TEXT_COLOR, fontSize: 12, fontFamily: FONT_FAMILY },
      extraCssText:
        'box-shadow: 0 4px 14px rgba(38,42,48,0.08); border-radius: 8px;'
    },
    grid: { left: 8, right: 12, top: 44, bottom: 0, containLabel: true },
    categoryAxis: {
      axisLine: { lineStyle: { color: BORDER_COLOR } },
      axisTick: { show: false },
      axisLabel: { color: TEXT_SUB, fontSize: 12, fontFamily: FONT_FAMILY },
      splitLine: { show: false }
    },
    valueAxis: {
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: TEXT_SUB, fontSize: 12, fontFamily: FONT_FAMILY },
      splitLine: { lineStyle: { color: SPLIT_COLOR, type: 'dashed' } },
      nameTextStyle: { color: TEXT_MUTED, fontSize: 12, fontFamily: FONT_FAMILY }
    },
    line: { symbol: 'circle', symbolSize: 6, smooth: true },
    bar: { itemStyle: { borderRadius: [4, 4, 0, 0] } },
    pie: { itemStyle: { borderColor: '#fdfcf8', borderWidth: 2 } }
  })
  return THEME_NAME
}

export const CHART_THEME_NAME = THEME_NAME

/** 纵向渐变填充（面积图用） */
export function verticalGradient(color, fromOpacity = 0.26, toOpacity = 0.02) {
  return {
    type: 'linear',
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: withAlpha(color, fromOpacity) },
      { offset: 1, color: withAlpha(color, toOpacity) }
    ]
  }
}

/** 横向渐变（条形图用：浅 -> 实） */
export function horizontalGradient(color, fromOpacity = 0.25, toOpacity = 1) {
  return {
    type: 'linear',
    x: 0,
    y: 0,
    x2: 1,
    y2: 0,
    colorStops: [
      { offset: 0, color: withAlpha(color, fromOpacity) },
      { offset: 1, color: withAlpha(color, toOpacity) }
    ]
  }
}

/** #rrggbb -> rgba() */
export function withAlpha(hex, alpha) {
  const value = hex.replace('#', '')
  const bigint = parseInt(value.length === 3 ? value.split('').map((c) => c + c).join('') : value, 16)
  const r = (bigint >> 16) & 255
  const g = (bigint >> 8) & 255
  const b = bigint & 255
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/** tooltip 行（label + 值 左右对齐），统一内联样式 */
export function tooltipRow(label, value, color) {
  return `<div style="display:flex;align-items:center;justify-content:space-between;gap:20px;line-height:20px">
    <span style="color:${TEXT_SUB}">${color ? `<i style="display:inline-block;width:7px;height:7px;border-radius:2px;background:${color};margin-right:6px"></i>` : ''}${label}</span>
    <b style="color:${TEXT_COLOR};font-variant-numeric:tabular-nums">${value}</b>
  </div>`
}

/** tooltip 标题 */
export function tooltipTitle(text, sub) {
  return `<div style="font-weight:600;color:${TEXT_COLOR};margin-bottom:6px">
    ${text}${sub ? `<span style="color:${TEXT_MUTED};font-weight:400;margin-left:6px">${sub}</span>` : ''}
  </div>`
}

/** 分隔线 */
export const tooltipDivider =
  `<div style="height:1px;background:${SPLIT_COLOR};margin:6px 0"></div>`
