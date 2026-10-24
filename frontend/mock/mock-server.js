/**
 * 本地 Mock 后端（仅用于前端联调/预览，不参与生产构建）
 * 启动：node mock/mock-server.js   -> 监听 http://localhost:8080/api
 * 实现接口文档中的 19 个接口，返回结构与字段完全对齐文档示例。
 */
import http from 'node:http'
import { URL } from 'node:url'

const PORT = 8080
const CATEGORIES = ['手机', '电脑', '服饰', '食品', '家电', '美妆']
const PRODUCTS = {
  手机: ['iPhone 15', '小米14 Pro', '华为Mate60'],
  电脑: ['联想拯救者Y7000', 'MacBook Air', 'ThinkPad X1'],
  服饰: ['优衣库羽绒服', '耐克运动鞋', '李宁卫衣'],
  食品: ['三只松鼠礼包', '伊利牛奶', '良品铺子零食'],
  家电: ['美的空调', '海尔冰箱', '戴森吹风机'],
  美妆: ['兰蔻精华', '雅诗兰黛面霜', '花西子口红']
}
const PAY_TYPES = ['alipay', 'wechat', 'card']
const REGIONS = ['广东', '浙江', '江苏', '北京', '上海', '四川', '山东', '湖北', '福建', '河南']

const rnd = (min, max) => Math.round(min + Math.random() * (max - min))
const pick = (list) => list[rnd(0, list.length - 1)]
const money = (value) => Number(Number(value).toFixed(2))
const ok = (data) => ({ code: 0, message: 'success', data })
const pad = (n) => String(n).padStart(2, '0')

/** 下单时间分布：近 14 个月，促销月(6/11/12)权重更高，小时呈双峰（10-12 点、20-23 点） */
const HOUR_POOL = [9, 10, 10, 11, 11, 12, 13, 14, 15, 16, 19, 20, 20, 21, 21, 22, 22, 23]
const PROMO_MONTHS = [6, 11, 12]

function randomOrderTime() {
  // 拒绝采样：让促销月的订单更密集
  let date
  for (let i = 0; i < 6; i++) {
    const offset = rnd(0, 419)
    const candidate = new Date(Date.now() - offset * 86400000)
    const boost = PROMO_MONTHS.includes(candidate.getMonth() + 1) ? 1.45 : 1
    if (Math.random() < boost / 1.45) {
      date = candidate
      break
    }
    date = candidate
  }
  const hour = pick(HOUR_POOL)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(hour)}:${pad(rnd(0, 59))}:${pad(rnd(0, 59))}`
}

/** 是否促销单：周末 / 促销月 / 大促区间 */
function isPromo(orderTime) {
  const month = Number(orderTime.slice(5, 7))
  const day = Number(orderTime.slice(8, 10))
  const weekday = new Date(`${orderTime.slice(0, 10)}T00:00:00Z`).getUTCDay()
  if (weekday === 0 || weekday === 6) return 1
  if (month === 6 && day <= 20) return 1
  if (month === 11 && day <= 12) return 1
  return 0
}

/** 生成一批订单（内存态，便于导入接口追加） */
function makeOrders(count = 5000) {
  const list = []
  for (let i = 0; i < count; i++) {
    const category = CATEGORIES[rnd(0, CATEGORIES.length - 1)]
    const names = PRODUCTS[category]
    const price = rnd(30, 6000)
    const quantity = rnd(1, 5)
    const orderTime = randomOrderTime()
    // 幂律用户分布：少数用户高频复购，复购率与 RFM 分层才有分析价值
    const userIndex = Math.floor(3450 * Math.pow(Math.random(), 2.6))
    list.push({
      orderId: `ORD${orderTime.slice(0, 10).replace(/-/g, '')}${pad(i % 999 + 1)}`,
      productName: names[rnd(0, names.length - 1)],
      category,
      price: money(price),
      quantity,
      totalAmount: money(price * quantity),
      orderTime,
      userId: `U${10001 + userIndex}`,
      userType: 'new',
      payType: PAY_TYPES[rnd(0, PAY_TYPES.length - 1)],
      promoFlag: isPromo(orderTime),
      region: REGIONS[rnd(0, REGIONS.length - 1)]
    })
  }

  // 新老用户判定：同一用户的首次下单记为 new，其后记为 old（口径与后端一致）
  const firstSeen = new Map()
  ;[...list]
    .sort((a, b) => a.orderTime.localeCompare(b.orderTime))
    .forEach((order) => {
      if (!firstSeen.has(order.userId)) firstSeen.set(order.userId, order.orderTime)
    })
  list.forEach((order) => {
    order.userType = firstSeen.get(order.userId) === order.orderTime ? 'new' : 'old'
  })

  return list
}

let ORDERS = makeOrders()
const TASKS = new Map()

/** 按全局筛选条件过滤（startDate/endDate/category/payType/userType/promoFlag/keyword） */
function filterOrders(q) {
  return ORDERS.filter((o) => {
    const day = o.orderTime.slice(0, 10)
    if (q.startDate && day < q.startDate) return false
    if (q.endDate && day > q.endDate) return false
    if (q.category && o.category !== q.category) return false
    if (q.payType && o.payType !== q.payType) return false
    if (q.userType && o.userType !== q.userType) return false
    if (q.promoFlag !== undefined && q.promoFlag !== '' && Number(q.promoFlag) !== o.promoFlag)
      return false
    if (q.keyword && !o.orderId.includes(q.keyword) && !o.productName.includes(q.keyword))
      return false
    return true
  })
}

const sum = (list, key) => money(list.reduce((total, item) => total + (item[key] || 0), 0))

/** 路由表：key = "METHOD 路径正则" */
const routes = [
  ['GET', /^\/api\/categories$/, () => ok(CATEGORIES.map((c) => ({ category: c, productCount: rnd(6, 24) })))],

  ['GET', /^\/api\/orders\/kpi$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const users = new Set(list.map((o) => o.userId))
    const repurchase = new Set()
    const counter = new Map()
    list.forEach((o) => counter.set(o.userId, (counter.get(o.userId) || 0) + 1))
    counter.forEach((count, userId) => count > 1 && repurchase.add(userId))
    const newUsers = new Set(list.filter((o) => o.userType === 'new').map((o) => o.userId)).size
    return ok({
      totalSales: sum(list, 'totalAmount'),
      totalOrders: list.length,
      avgOrderValue: list.length ? money(sum(list, 'totalAmount') / list.length) : 0,
      hotProductCount: new Set(list.map((o) => o.productName)).size,
      totalUsers: users.size,
      newUserRate: users.size ? Number((newUsers / users.size).toFixed(4)) : null,
      repurchaseRate: users.size ? Number((repurchase.size / users.size).toFixed(4)) : null
    })
  }],

  ['GET', /^\/api\/orders\/ranking$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const map = new Map()
    list.forEach((o) => {
      const row = map.get(o.productName) || { productName: o.productName, sales: 0, quantity: 0 }
      row.sales = money(row.sales + o.totalAmount)
      row.quantity += o.quantity
      map.set(o.productName, row)
    })
    const rankBy = ctx.query.rankBy || 'sales'
    const limit = Math.min(Number(ctx.query.limit) || 10, 50)
    return ok(
      [...map.values()]
        .sort((a, b) => b[rankBy] - a[rankBy])
        .slice(0, limit)
        .map((row, index) => ({ rank: index + 1, ...row }))
    )
  }],

  ['GET', /^\/api\/orders\/stats$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const granularity = ctx.query.granularity || 'day'
    const map = new Map()
    list.forEach((o) => {
      const day = o.orderTime.slice(0, 10)
      const key =
        granularity === 'month'
          ? day.slice(0, 7)
          : granularity === 'week'
            ? `2026-W${pad(Math.ceil(Number(day.slice(8, 10)) / 7))}`
            : day
      const row = map.get(key) || { period: key, sales: 0, orders: 0 }
      row.sales = money(row.sales + o.totalAmount)
      row.orders += 1
      map.set(key, row)
    })
    return ok({ granularity, items: [...map.values()].sort((a, b) => a.period.localeCompare(b.period)) })
  }],

  ['GET', /^\/api\/orders\/trend$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const map = new Map()
    list.forEach((o) => {
      const day = o.orderTime.slice(0, 10)
      const row = map.get(day) || { period: day, currentSales: 0, currentOrders: 0 }
      row.currentSales = money(row.currentSales + o.totalAmount)
      row.currentOrders += 1
      map.set(day, row)
    })
    const items = [...map.values()]
      .sort((a, b) => a.period.localeCompare(b.period))
      .map((row, index) => {
        const prevSales = money(row.currentSales * (0.7 + Math.random() * 0.5))
        const prevOrders = Math.round(row.currentOrders * (0.75 + Math.random() * 0.5))
        return {
          ...row,
          prevSales,
          prevOrders,
          // 前端需兼容 null：第一天的增长率故意返回 null
          salesGrowthRate: index === 0 ? null : Number(((row.currentSales - prevSales) / prevSales).toFixed(4)),
          ordersGrowthRate: index === 0 ? null : Number(((row.currentOrders - prevOrders) / prevOrders).toFixed(4))
        }
      })
    return ok({
      granularity: ctx.query.granularity || 'day',
      compareType: ctx.query.compareType || 'previous',
      currentPeriod: { startDate: ctx.query.startDate, endDate: ctx.query.endDate },
      comparePeriod: { startDate: '2026-08-01', endDate: '2026-08-31' },
      items
    })
  }],

  ['GET', /^\/api\/orders\/price-distribution$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const ranges = [
      ['0-100', 0, 100],
      ['100-300', 100, 300],
      ['300-500', 300, 500],
      ['500-1000', 500, 1000],
      ['1000-5000', 1000, 5000],
      ['5000以上', 5000, Infinity]
    ]
    const totalSales = sum(list, 'totalAmount')
    return ok({
      totalOrders: list.length,
      totalSales,
      intervals: ranges.map(([range, min, max]) => {
        const rows = list.filter((o) => o.totalAmount >= min && o.totalAmount < max)
        return {
          range,
          orderCount: rows.length,
          sales: sum(rows, 'totalAmount'),
          orderRatio: list.length ? Number((rows.length / list.length).toFixed(4)) : 0
        }
      })
    })
  }],

  ['GET', /^\/api\/orders\/category-contribution$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const totalSales = sum(list, 'totalAmount')
    const map = new Map()
    list.forEach((o) => map.set(o.category, money((map.get(o.category) || 0) + o.totalAmount)))
    let cumulative = 0
    return ok({
      totalSales,
      items: [...map.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(([category, sales], index) => {
          const salesRatio = totalSales ? sales / totalSales : 0
          cumulative += salesRatio
          return {
            rank: index + 1,
            category,
            sales,
            salesRatio: Number(salesRatio.toFixed(4)),
            cumulativeRatio: Number(cumulative.toFixed(4))
          }
        })
    })
  }],

  ['GET', /^\/api\/orders\/hourly$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const hours = Array.from({ length: 24 }, (_, hour) => ({ hour, orderCount: 0, sales: 0 }))
    list.forEach((o) => {
      const hour = Number(o.orderTime.slice(11, 13))
      hours[hour].orderCount += 1
      hours[hour].sales = money(hours[hour].sales + o.totalAmount)
    })
    return ok({ hours })
  }],

  ['GET', /^\/api\/orders\/user-analysis$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const newRows = list.filter((o) => o.userType === 'new')
    const oldRows = list.filter((o) => o.userType === 'old')
    const users = new Set(list.map((o) => o.userId))
    const counter = new Map()
    list.forEach((o) => counter.set(o.userId, (counter.get(o.userId) || 0) + 1))
    const repurchase = [...counter.values()].filter((c) => c > 1).length
    const payMap = new Map()
    list.forEach((o) => payMap.set(o.payType, (payMap.get(o.payType) || 0) + 1))
    return ok({
      totalUsers: users.size,
      newUserCount: new Set(newRows.map((o) => o.userId)).size,
      oldUserCount: new Set(oldRows.map((o) => o.userId)).size,
      newUserSales: sum(newRows, 'totalAmount'),
      oldUserSales: sum(oldRows, 'totalAmount'),
      newUserAvgOrderValue: newRows.length ? money(sum(newRows, 'totalAmount') / newRows.length) : 0,
      oldUserAvgOrderValue: oldRows.length ? money(sum(oldRows, 'totalAmount') / oldRows.length) : 0,
      newUserRate: users.size ? Number((new Set(newRows.map((o) => o.userId)).size / users.size).toFixed(4)) : null,
      repurchaseRate: users.size ? Number((repurchase / users.size).toFixed(4)) : null,
      payTypeRates: [...payMap.entries()].map(([payType, orderCount]) => ({
        payType,
        orderCount,
        rate: list.length ? Number((orderCount / list.length).toFixed(4)) : 0
      }))
    })
  }],

  ['GET', /^\/api\/orders\/campaign-effect$/, (q, ctx) => {
    // 活动期 = 传入区间；非活动期 = 活动期前等长时间段（与文档 5.15 口径一致）
    const start = ctx.query.startDate
    const end = ctx.query.endDate
    const days = Math.max(1, Math.round((new Date(end) - new Date(start)) / 86400000) + 1)
    const prevEndDate = new Date(new Date(start).getTime() - 86400000)
    const prevStartDate = new Date(prevEndDate.getTime() - (days - 1) * 86400000)
    const fmt = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

    const activity = filterOrders({ category: ctx.query.category, startDate: start, endDate: end })
    const nonActivity = filterOrders({
      category: ctx.query.category,
      startDate: fmt(prevStartDate),
      endDate: fmt(prevEndDate)
    })
    const build = (rows) => ({
      sales: sum(rows, 'totalAmount'),
      orders: rows.length,
      avgOrderValue: rows.length ? money(sum(rows, 'totalAmount') / rows.length) : 0,
      promoOrderCount: rows.filter((o) => o.promoFlag === 1).length
    })
    const a = build(activity)
    const n = build(nonActivity)
    a.promoOrderRate = activity.length ? Number((a.promoOrderCount / activity.length).toFixed(4)) : 0
    return ok({
      activityPeriod: { startDate: start, endDate: end },
      nonActivityPeriod: { startDate: fmt(prevStartDate), endDate: fmt(prevEndDate) },
      activity: a,
      nonActivity: n,
      salesGrowthRate: n.sales ? Number(((a.sales - n.sales) / n.sales).toFixed(4)) : null,
      ordersGrowthRate: n.orders ? Number(((a.orders - n.orders) / n.orders).toFixed(4)) : null,
      promotionPullRate: a.sales ? Number(((a.sales * a.promoOrderRate) / a.sales).toFixed(4)) : null
    })
  }],

  ['GET', /^\/api\/orders\/region$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const totalSales = sum(list, 'totalAmount')
    const map = new Map()
    list.forEach((o) => {
      const row = map.get(o.region) || { region: o.region, sales: 0, orderCount: 0 }
      row.sales = money(row.sales + o.totalAmount)
      row.orderCount += 1
      map.set(o.region, row)
    })
    return ok({
      items: [...map.values()]
        .sort((a, b) => b.sales - a.sales)
        .map((row, index) => ({
          rank: index + 1,
          ...row,
          salesRatio: totalSales ? Number((row.sales / totalSales).toFixed(4)) : 0
        }))
    })
  }],

  ['GET', /^\/api\/orders\/export$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const header = '订单号,商品名,品类,单价,数量,总金额,下单时间,用户ID,用户类型,支付方式'
    const lines = list.map((o) =>
      [o.orderId, o.productName, o.category, o.price, o.quantity, o.totalAmount, o.orderTime, o.userId, o.userType, o.payType].join(',')
    )
    return { csv: `\uFEFF${[header, ...lines].join('\n')}`, filename: `orders_${ctx.query.startDate}_${ctx.query.endDate}.csv` }
  }],

  ['GET', /^\/api\/targets$/, (q, ctx) => {
    const periodValue = ctx.query.periodValue || '2026-09'
    return ok({
      periodType: 'month',
      periodValue,
      items: [
        { id: 1, category: 'ALL', targetAmount: 600000.0 },
        ...CATEGORIES.map((category, index) => ({ id: index + 2, category, targetAmount: 100000 + index * 30000 }))
      ]
    })
  }],

  ['GET', /^\/api\/orders\/target-progress$/, (q, ctx) => {
    const periodValue = ctx.query.periodValue || '2026-09'
    const achieved = sum(filterOrders(ctx.query), 'totalAmount')
    const targets = [{ category: 'ALL', targetAmount: 600000 }, ...CATEGORIES.map((c, i) => ({ category: c, targetAmount: 100000 + i * 30000 }))]
    const totalTarget = 600000
    return ok({
      periodType: 'month',
      periodValue,
      totalTarget,
      achievedSales: achieved,
      achievementRate: Number((achieved / totalTarget).toFixed(4)),
      remainingDays: 14,
      dailyNeeded: money(Math.max(0, totalTarget - achieved) / 14),
      items: targets.map((item) => {
        const rows = filterOrders({ ...ctx.query, category: item.category === 'ALL' ? '' : item.category })
        const value = sum(rows, 'totalAmount')
        return {
          category: item.category,
          targetAmount: item.targetAmount,
          achieved: value,
          achievementRate: Number((value / item.targetAmount).toFixed(4))
        }
      })
    })
  }],

  ['GET', /^\/api\/users\/rfm$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const windowEnd = ctx.query.endDate || new Date().toISOString().slice(0, 10)
    const map = new Map()
    list.forEach((o) => {
      const row = map.get(o.userId) || { userId: o.userId, count: 0, sales: 0, lastOrder: o.orderTime }
      row.count += 1
      row.sales += o.totalAmount
      if (o.orderTime > row.lastOrder) row.lastOrder = o.orderTime
      map.set(o.userId, row)
    })
    const users = [...map.values()].map((u) => ({
      ...u,
      recency: Math.max(
        0,
        Math.round((new Date(windowEnd) - new Date(u.lastOrder.slice(0, 10))) / 86400000)
      )
    }))

    // 分位阈值（中位数，抗极值）：R/F/M 各按中位数二分，组合成 8 类
    const median = (values) => {
      if (!values.length) return 0
      const sorted = [...values].sort((a, b) => a - b)
      const mid = Math.floor(sorted.length / 2)
      return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
    }
    const thresholds = {
      rDays: Math.round(median(users.map((u) => u.recency))),
      fCount: Math.round(median(users.map((u) => u.count))),
      mAmount: money(median(users.map((u) => u.sales)))
    }

    const SEGMENT_NAME = {
      '111': '重要价值客户',
      '011': '重要保持客户',
      '101': '重要发展客户',
      '001': '重要挽留客户',
      '110': '一般价值客户',
      '010': '一般保持客户',
      '100': '一般发展客户',
      '000': '流失客户'
    }
    const bucket = new Map()
    users.forEach((u) => {
      const r = u.recency <= thresholds.rDays ? 1 : 0 // 1 = 最近有购买
      const f = u.count >= Math.max(1, thresholds.fCount) ? 1 : 0
      const m = u.sales >= thresholds.mAmount ? 1 : 0
      const key = `${r}${f}${m}`
      const entry = bucket.get(key) || { segment: SEGMENT_NAME[key], count: 0, sales: 0, orders: 0 }
      entry.count += 1
      entry.sales += u.sales
      entry.orders += u.count
      bucket.set(key, entry)
    })

    const totalSales = sum(users, 'sales')
    const segments = [...bucket.values()]
      .map((entry) => ({
        segment: entry.segment,
        userCount: entry.count,
        sales: money(entry.sales),
        salesRatio: totalSales ? Number((entry.sales / totalSales).toFixed(4)) : 0,
        avgOrderValue: entry.orders ? money(entry.sales / entry.orders) : 0,
        avgFrequency: entry.count ? Number((entry.orders / entry.count).toFixed(1)) : 0
      }))
      .sort((a, b) => b.sales - a.sales)

    return ok({
      windowStart: ctx.query.startDate,
      windowEnd,
      thresholds,
      totalUsers: users.length,
      totalSales,
      segments
    })
  }],

  ['POST', /^\/api\/orders\/import$/, (q, ctx, body) => {
    const taskId = `imp_${Date.now()}`
    const totalRows = 5200
    const errorRows = 20
    TASKS.set(taskId, { startTime: new Date() })
    // 模拟异步任务：3 秒后变为终态
    setTimeout(() => TASKS.get(taskId) && TASKS.set(taskId, { ...TASKS.get(taskId), done: true }), 3000)
    return ok({ taskId, status: 'PROCESSING', append: body?.append, totalRows, errorRows })
  }],

  ['GET', /^\/api\/orders\/import\/tasks\/(.+)$/, (q, ctx, body, params) => {
    const task = TASKS.get(params[0])
    if (!task) return { code: 404, message: '导入任务不存在' }
    const finished = !!task.done
    const fmt = (d) =>
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    return ok({
      taskId: params[0],
      status: finished ? 'PARTIAL' : 'PROCESSING',
      totalRows: 5200,
      validRows: finished ? 5180 : 0,
      errorRows: finished ? 20 : 0,
      startTime: fmt(task.startTime),
      endTime: finished ? fmt(new Date()) : null,
      errors: finished
        ? [
            { row: 12, field: 'totalAmount', message: '总金额与单价×数量不一致' },
            { row: 45, field: 'orderTime', message: '日期格式非法' },
            { row: 108, field: 'payType', message: '支付方式枚举非法' }
          ]
        : []
    })
  }],

  ['GET', /^\/api\/orders$/, (q, ctx) => {
    const list = filterOrders(ctx.query)
    const page = Math.max(1, Number(ctx.query.page) || 1)
    const size = Math.min(100, Number(ctx.query.size) || 10)
    const [sortField, sortOrder] = (ctx.query.sort || 'orderTime,desc').split(',')
    const sorted = [...list].sort((a, b) => {
      const av = a[sortField]
      const bv = b[sortField]
      const result = typeof av === 'number' ? av - bv : String(av).localeCompare(String(bv))
      return sortOrder === 'asc' ? result : -result
    })
    return ok({ total: list.length, page, size, records: sorted.slice((page - 1) * size, page * size) })
  }],

  ['GET', /^\/api\/orders\/(.+)$/, (q, ctx, body, params) => {
    const order = ORDERS.find((o) => o.orderId === decodeURIComponent(params[0]))
    return order ? ok(order) : { code: 404, message: '订单不存在' }
  }]
]

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  const query = Object.fromEntries(url.searchParams.entries())
  const ctx = { query }

  const match = routes
    .map(([method, pattern, handler]) => ({ method, pattern, handler, params: url.pathname.match(pattern) }))
    .find((route) => route.params && route.method === req.method)

  // CORS：允许前端开发服务器直连
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
  if (req.method === 'OPTIONS') return res.writeHead(204).end()

  if (!match) {
    res.writeHead(404, { 'Content-Type': 'application/json; charset=UTF-8' })
    return res.end(JSON.stringify({ code: 404, message: `未实现的接口：${url.pathname}` }))
  }

  // 读取请求体（multipart 只做简单统计，不做真实解析）
  let raw = ''
  req.on('data', (chunk) => (raw += chunk))
  req.on('end', () => {
    const body = raw ? Object.fromEntries(new URLSearchParams(raw).entries()) : {}
    let result
    try {
      result = match.handler(query, ctx, body, match.params.slice(1))
    } catch (e) {
      result = { code: 500, message: e.message }
    }

    if (result && result.csv !== undefined) {
      res.writeHead(200, {
        'Content-Type': 'text/csv; charset=UTF-8',
        'Content-Disposition': `attachment; filename="${result.filename}"`
      })
      return res.end(result.csv)
    }
    const payload = result && result.code !== undefined ? result : ok(result)
    res.writeHead(payload.code === 0 ? 200 : payload.code, { 'Content-Type': 'application/json; charset=UTF-8' })
    res.end(JSON.stringify(payload))
  })
})

server.listen(PORT, () => {
  console.log(`Mock API server running: http://localhost:${PORT}/api`)
  console.log(`orders in memory: ${ORDERS.length}`)
})
