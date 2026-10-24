/**
 * ECharts 按需注册（统一入口）
 * 全量 echarts 约 1MB，本项目只用到 line / bar / pie / gauge / map 五类图表，
 * 通过 echarts/core 只注册所需模块，显著减小打包体积。
 * 所有图表组件统一从本文件取 echarts 实例，勿再直接 import 'echarts'。
 */
import * as echarts from 'echarts/core'
import { BarChart, GaugeChart, LineChart, MapChart, PieChart } from 'echarts/charts'
import {
  GraphicComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  MarkPointComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  // 图表
  LineChart,
  BarChart,
  PieChart,
  GaugeChart,
  MapChart,
  // 组件
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  VisualMapComponent,
  MarkLineComponent,
  // 峰值打点用（漏注册会让 markPoint 静默失效，不报错，排查很费劲）
  MarkPointComponent,
  GraphicComponent,
  // 渲染器
  CanvasRenderer
])

export default echarts
