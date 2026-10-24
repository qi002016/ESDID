import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// ECharts 按需在各图表组件中引入 echarts/core，保持打包体积可控
import App from './App.vue'
import '@/styles/index.scss'

const app = createApp(App)

app.use(createPinia())
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
