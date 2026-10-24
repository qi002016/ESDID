# 电商销售数据洞察看板（前端）

Vue3 + `<script setup>` + Vite + Element Plus + ECharts 5 + Axios + Pinia + dayjs。

## 快速开始

```bash
npm install --cache D:\ESDID\.npm-cache   # 本机 npm 默认缓存目录无写权限，需指定 --cache
npm run dev                               # http://localhost:5173（/api 已代理到 :8080）
npm run build                             # 产物输出 dist/
node mock/mock-server.js                  # 可选：本地 Mock 后端（19 个接口），无后端时预览用
```

## 目录结构

```
src
├─ api            # 19 个接口封装：category(A1) order(A2/A6) kpi(A3) chart(A4/A5/A7-A11/A15-A17)
│                 #                target(A13/A14) file(A12/A18/A19)
├─ assets/geo     # 中国地图 GeoJSON（ECharts 5 需自行 registerMap）
├─ components
│  ├─ DashboardKpiCard.vue   # A3 KPI 卡片
│  ├─ FilterBar.vue          # 全局筛选栏（含 A12 导出按钮）
│  ├─ charts/                # 10 个图表组件 + ChartCard 容器 + useChart 组合式封装
│  ├─ OrderTable.vue         # A2 分页表格（含 A6 下钻、A12 导出）
│  ├─ OrderDetailDialog.vue  # A6 订单详情弹窗
│  └─ ImportModal.vue        # A18 上传 + A19 轮询
├─ store/dashboard.js        # 全局筛选条件 + revision 版本号驱动全量刷新
├─ utils/                    # request（拦截器）/ download（CSV）/ format（金额、日期）
└─ views/DashboardIndex.vue  # 看板首页
```

## 代码约定

1. **统一响应处理**：`utils/request.js` 拦截器判断 `code !== 0` 并 `ElMessage` 提示，业务组件不再写 `if (code !== 0)`；`responseType: 'blob'` 时直通整个 response，Blob 内若为 JSON 错误会自动解析提示。
2. **参数命名严格对齐接口文档**：`page`、`size`、`category`、`keyword`、`payType`、`userType`、`promoFlag`、`startDate`、`endDate`、`sort`、`granularity`、`compareType`、`rankBy`、`periodType`、`periodValue`、`limit`、`append`。
3. **全局刷新机制**：`store.setFilters()` 会 `revision++`，所有图表与表格 `watch(revision)` 后统一重新请求；翻页/排序只刷新表格。
4. **边界处理**：后端返回 `null` / 空数组 → 图表显示 `el-empty` 空状态；增长率为 `null` → 展示 `--`；金额一律 2 位小数。
5. **内存泄漏防护**：ECharts 实例在 `onBeforeUnmount` 中 `dispose`；导入轮询定时器在 `onUnmounted` 与弹窗关闭时清除。
