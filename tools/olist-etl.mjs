#!/usr/bin/env node
/**
 * Olist -> 本项目订单模型 的 ETL 映射脚本（零依赖，Node 18+）
 * ============================================================================
 * 输入：Kaggle「Brazilian E-Commerce Public Dataset by Olist」原始 CSV
 *   olist_orders_dataset.csv            order_id, customer_id, order_status, order_purchase_timestamp, ...
 *   olist_order_items_dataset.csv       order_id, order_item_id, product_id, seller_id, shipping_limit_date, price, freight_value
 *   olist_order_payments_dataset.csv    order_id, payment_sequential, payment_type, payment_installments, payment_value
 *   olist_customers_dataset.csv         customer_id, customer_unique_id, customer_zip_code_prefix, customer_city, customer_state
 *   olist_products_dataset.csv          product_id, product_category_name, ...
 *   product_category_name_translation.csv  product_category_name, product_category_name_english
 *
 * 输出（默认目录 data/out）：
 *   orders.csv               列顺序 = 订单号,商品名,品类,单价,数量,总金额,下单时间,用户ID,用户类型,支付方式
 *                            （可直接通过 A18 CSV 批量导入接口灌入后端）
 *   init-data.sql            orders + sales_target 的 INSERT（可选 --ddl 附带建表语句）
 *   data-quality-report.md   数据质量与口径说明（答辩/文档可直接引用）
 *
 * 用法：
 *   node tools/olist-etl.mjs --source data/olist --out data/out
 *   node tools/olist-etl.mjs --help
 * ============================================================================
 */

import fs from 'node:fs'
import path from 'node:path'

// ---------------------------------------------------------------------------
// 一、口径配置（需要调整时改这里即可）
// ---------------------------------------------------------------------------

/** BRL -> CNY 汇率（1 巴西雷亚尔 ≈ 1.45 人民币，设为 1 表示不换算） */
const DEFAULT_RATE = 1.45

/** 保留的订单状态（其余视为无效订单，如 canceled / unavailable） */
const DEFAULT_STATUSES = ['delivered', 'shipped', 'invoiced', 'processing', 'approved']

/**
 * 品类归并：Olist 英文品类 -> 接口文档示例的 6 大类
 * 说明：文档 3.1 的 6 个品类是「如...等」的示例，这里做归并是为了让前端筛选下拉更聚焦；
 * 未被覆盖的品类落入「其他」；如要保留原始英文品类名，用 --category-mode original
 */
const CATEGORY_MAP = {
  // 手机
  telephony: '手机',
  // 电脑
  computers: '电脑',
  computers_accessories: '电脑',
  tablets_printing_image: '电脑',
  pc_gamer: '电脑',
  electronics: '电脑',
  // 服饰（含鞋包、运动户外）
  fashion_bags_accessories: '服饰',
  fashion_shoes: '服饰',
  fashion_underwear_beach: '服饰',
  fashion_male_clothing: '服饰',
  fashion_female_clothing: '服饰',
  fashion_childrens_clothes: '服饰',
  luggage_accessories: '服饰',
  watches_gifts: '服饰',
  sports_leisure: '服饰',
  // 食品（含饮品）
  food: '食品',
  food_drink: '食品',
  drinks: '食品',
  la_cuisine: '食品',
  // 家电（含家居、厨具、家装工具：文档 6 大类无「家居」，统一归入家电）
  home_appliances: '家电',
  home_appliances_2: '家电',
  small_appliances: '家电',
  small_appliances_home_oven_and_coffee: '家电',
  air_conditioning: '家电',
  housewares: '家电',
  furniture_decor: '家电',
  bed_bath_table: '家电',
  construction: '家电',
  garden_tools: '家电',
  lighting: '家电',
  signaling_and_security: '家电',
  home_construction: '家电',
  // 美妆（含个护、香水）
  health_beauty: '美妆',
  perfumery: '美妆',
  diapers_and_hygiene: '美妆'
}

/** 币种换算后的价格展示区间（用于报告校验，超出则提示） */
const PRICE_SANITY = { min: 0.01, max: 200000 }

/** 巴西州 -> 中国省份（仅 --region-mode cn 时使用，属于「演示映射」，报告中会标注） */
const BR_TO_CN_REGION = {
  SP: '广东', RJ: '浙江', MG: '江苏', RS: '山东', PR: '福建', SC: '河南',
  BA: '四川', DF: '北京', ES: '上海', GO: '湖北', PE: '湖南', CE: '河北',
  PA: '安徽', MT: '陕西', MA: '辽宁', MS: '江西', PB: '广西', PI: '云南',
  RN: '贵州', AL: '山西', SE: '天津', TO: '重庆', RO: '新疆', AM: '内蒙古',
  AC: '甘肃', AP: '海南', RR: '宁夏'
}

/** 支付方式映射（Olist -> 文档枚举 alipay/wechat/card） */
const PAYTYPE_MAP = {
  credit_card: 'card',
  debit_card: 'card',
  boleto: 'alipay',
  voucher: 'wechat',
  not_defined: 'card'
}

// ---------------------------------------------------------------------------
// 二、CLI 参数
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = {
    source: 'data/olist',
    out: 'data/out',
    from: '',
    to: '',
    rate: DEFAULT_RATE,
    regionMode: 'br', // br: 保留巴西州码（真实，前端 A17 走排行榜降级） / cn: 映射为中国省份（演示地图）
    promoRule: 'br', // br: 黑色星期五+周末 / cn: 618+双11+周末
    categoryMode: 'doc6', // doc6: 归并到文档 6 大类 / original: 保留英文品类名
    statuses: DEFAULT_STATUSES.join(','),
    limit: 0,
    header: true,
    bom: false,
    ddl: false
  }
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i]
    const value = argv[i + 1]
    switch (key) {
      case '--source': args.source = value; i++; break
      case '--out': args.out = value; i++; break
      case '--from': args.from = value; i++; break
      case '--to': args.to = value; i++; break
      case '--rate': args.rate = Number(value); i++; break
      case '--region-mode': args.regionMode = value; i++; break
      case '--promo-rule': args.promoRule = value; i++; break
      case '--category-mode': args.categoryMode = value; i++; break
      case '--statuses': args.statuses = value; i++; break
      case '--limit': args.limit = Number(value); i++; break
      case '--no-header': args.header = false; break
      case '--bom': args.bom = true; break
      case '--ddl': args.ddl = true; break
      case '--help': case '-h': printHelp(); process.exit(0); break
      default:
        if (key.startsWith('--')) {
          console.error(`未知参数：${key}，使用 --help 查看用法`)
          process.exit(1)
        }
    }
  }
  args.statusList = args.statuses.split(',').map((s) => s.trim()).filter(Boolean)
  return args
}

function printHelp() {
  console.log(`
Olist -> 本项目订单模型 ETL

用法：
  node tools/olist-etl.mjs --source data/olist --out data/out

参数：
  --source <dir>          Olist 原始 CSV 目录（默认 data/olist）
  --out <dir>             输出目录（默认 data/out）
  --from <yyyy-MM-dd>     只保留该日期之后（含）的订单
  --to <yyyy-MM-dd>       只保留该日期之前（含）的订单
  --rate <num>            BRL->CNY 汇率，默认 ${DEFAULT_RATE}；设 1 表示不换算
  --region-mode br|cn     br(默认) 保留巴西州码；cn 映射为中国省份（演示地图用）
  --promo-rule br|cn      br(默认) 黑五+周末；cn 618+双11+周末
  --category-mode doc6|original
                          doc6(默认) 归并到文档 6 大类；original 保留英文品类名
  --statuses <a,b,c>      保留的订单状态，默认 ${DEFAULT_STATUSES.join(',')}
  --limit <n>             只处理前 n 单（调试用）
  --no-header             orders.csv 不写表头
  --bom                   orders.csv 写入 UTF-8 BOM（便于 Excel 打开；导入后端建议不加）
  --ddl                   init-data.sql 中附带 CREATE TABLE IF NOT EXISTS
  --help                  显示帮助
`)
}

// ---------------------------------------------------------------------------
// 三、工具函数
// ---------------------------------------------------------------------------

/** 标准 CSV 解析（支持引号包裹、字段内逗号/换行/双引号转义） */
function parseCsv(text) {
  const content = text.replace(/^\uFEFF/, '')
  const rows = []
  let row = []
  let field = ''
  let inQuotes = false
  for (let i = 0; i < content.length; i++) {
    const ch = content[i]
    if (inQuotes) {
      if (ch === '"') {
        if (content[i + 1] === '"') { field += '"'; i++ } else { inQuotes = false }
      } else {
        field += ch
      }
    } else if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      row.push(field); field = ''
    } else if (ch === '\n') {
      row.push(field); rows.push(row); row = []; field = ''
    } else if (ch !== '\r') {
      field += ch
    }
  }
  if (field !== '' || row.length > 0) { row.push(field); rows.push(row) }
  return rows
}

/** CSV 文本 -> 对象数组（首行为表头） */
function readCsvAsObjects(file) {
  if (!fs.existsSync(file)) {
    throw new Error(`缺少必要文件：${file}\n请先按 docs/数据获取与ETL说明.md 下载 Olist 数据集`)
  }
  const rows = parseCsv(fs.readFileSync(file, 'utf8'))
  const header = rows.shift().map((h) => h.trim())
  return rows
    .filter((row) => row.length > 1)
    .map((row) => {
      const record = {}
      header.forEach((key, index) => { record[key] = (row[index] ?? '').trim() })
      return record
    })
}

/** CSV 字段转义 */
function csvField(value) {
  const text = value === null || value === undefined ? '' : String(value)
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/** 金额：四舍五入到 2 位小数（避免浮点误差） */
const round2 = (value) => Math.round((Number(value) + Number.EPSILON) * 100) / 100

/** 稳定哈希 -> [min, max) 区间的确定性因子（保证脚本可重复执行结果一致） */
function hashFactor(text, min, max) {
  let hash = 2166136261
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  const ratio = (hash >>> 0) % 10000 / 10000
  return min + ratio * (max - min)
}

/** 是否为周末（用于促销标记） */
function isWeekend(dateText) {
  const date = new Date(`${dateText.slice(0, 10)}T00:00:00Z`)
  const day = date.getUTCDay()
  return day === 0 || day === 6
}

/** 促销标记规则：返回 1/0 */
function calcPromoFlag(orderTime, rule) {
  const date = orderTime.slice(0, 10)
  const monthDay = date.slice(5)
  if (rule === 'cn') {
    // 618 大促（6/16~6/20）+ 双11（11/1~11/12）+ 周末
    if (monthDay >= '06-16' && monthDay <= '06-20') return 1
    if (monthDay >= '11-01' && monthDay <= '11-12') return 1
  } else {
    // 巴西黑色星期五（11 月最后一周）+ 圣诞周 + 周末
    if (monthDay >= '11-23' && monthDay <= '11-30') return 1
    if (monthDay >= '12-20' && monthDay <= '12-25') return 1
  }
  return isWeekend(date) ? 1 : 0
}

// ---------------------------------------------------------------------------
// 四、主流程
// ---------------------------------------------------------------------------

function main() {
  const args = parseArgs(process.argv.slice(2))
  const sourceDir = path.resolve(args.source)
  const outDir = path.resolve(args.out)

  log(`读取 Olist 原始数据：${sourceDir}`)
  const rawOrders = readCsvAsObjects(path.join(sourceDir, 'olist_orders_dataset.csv'))
  const rawItems = readCsvAsObjects(path.join(sourceDir, 'olist_order_items_dataset.csv'))
  const rawPayments = readCsvAsObjects(path.join(sourceDir, 'olist_order_payments_dataset.csv'))
  const rawCustomers = readCsvAsObjects(path.join(sourceDir, 'olist_customers_dataset.csv'))
  const rawProducts = readCsvAsObjects(path.join(sourceDir, 'olist_products_dataset.csv'))
  const rawTranslation = readCsvAsObjects(path.join(sourceDir, 'product_category_name_translation.csv'))
  log(`  订单 ${rawOrders.length} / 明细 ${rawItems.length} / 支付 ${rawPayments.length} / 客户 ${rawCustomers.length} / 商品 ${rawProducts.length}`)

  // 4.1 索引：客户、商品、品类翻译、支付
  const customerStateById = new Map()
  const customerUniqueById = new Map()
  rawCustomers.forEach((row) => {
    customerStateById.set(row.customer_id, row.customer_state)
    customerUniqueById.set(row.customer_id, row.customer_unique_id)
  })

  const translationByPt = new Map()
  rawTranslation.forEach((row) => {
    translationByPt.set(row.product_category_name, row.product_category_name_english)
  })

  const categoryByProduct = new Map()
  rawProducts.forEach((row) => {
    const english = translationByPt.get(row.product_category_name) || row.product_category_name || ''
    categoryByProduct.set(row.product_id, english)
  })

  // 支付方式：一个订单可能多笔支付，取金额最大的一笔（主支付方式）
  const paymentByOrder = new Map()
  rawPayments.forEach((row) => {
    const amount = Number(row.payment_value) || 0
    const current = paymentByOrder.get(row.order_id)
    if (!current || amount > current.amount) {
      paymentByOrder.set(row.order_id, { amount, type: row.payment_type })
    }
  })

  // 4.2 订单明细聚合：一个订单可能多行（多商品/多件）
  const itemsByOrder = new Map()
  rawItems.forEach((row) => {
    const price = Number(row.price) || 0
    const entry = itemsByOrder.get(row.order_id) || { units: 0, sumPrice: 0, topProductId: '', topPrice: -1 }
    entry.units += 1
    entry.sumPrice += price
    if (price > entry.topPrice) { entry.topPrice = price; entry.topProductId = row.product_id }
    itemsByOrder.set(row.order_id, entry)
  })

  // 4.3 逐单转换为目标模型
  const draft = []
  let skippedStatus = 0
  let skippedNoItem = 0
  let skippedDate = 0

  const statusSet = new Set(args.statusList)
  rawOrders.forEach((order) => {
    if (!statusSet.has(order.order_status)) { skippedStatus++; return }

    const items = itemsByOrder.get(order.order_id)
    if (!items) { skippedNoItem++; return }

    const orderTime = (order.order_purchase_timestamp || '').trim()
    const dateText = orderTime.slice(0, 10)
    if (!/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(orderTime)) { skippedDate++; return }
    if (args.from && dateText < args.from) { skippedDate++; return }
    if (args.to && dateText > args.to) { skippedDate++; return }

    // 单价 = 明细金额之和 / 件数（加权均价），随后用「单价 × 数量」重算总金额，
    // 保证满足 A18 校验规则：总金额与单价×数量一致（容差 0.01）
    const rate = args.rate
    const unitPrice = round2((items.sumPrice / items.units) * rate)
    const quantity = items.units
    const totalAmount = round2(unitPrice * quantity)

    const englishCategory = categoryByProduct.get(items.topProductId) || ''
    const category =
      args.categoryMode === 'original'
        ? (englishCategory || 'unknown')
        : (CATEGORY_MAP[englishCategory] || '其他')

    // 商品名：Olist 无商品名称字段，用「品类 + SKU 短码」构造，保证同名商品可区分
    const sku = (items.topProductId || '').slice(0, 6).toUpperCase()
    const productName = category === '其他' && englishCategory
      ? `${englishCategory} ${sku}`
      : `${category} ${sku}`

    const payment = paymentByOrder.get(order.order_id)
    const payType = PAYTYPE_MAP[payment?.type] || 'card'

    draft.push({
      orderId: order.order_id,
      productName,
      category,
      price: unitPrice,
      quantity,
      totalAmount,
      orderTime,
      customerUniqueId: customerUniqueById.get(order.customer_id) || order.customer_id,
      payType,
      promoFlag: calcPromoFlag(orderTime, args.promoRule),
      regionBr: customerStateById.get(order.customer_id) || '',
      paymentTypeRaw: payment?.type || 'missing'
    })
  })

  // 4.4 排序（按下单时间升序，便于新老用户判定）并按 limit 截断
  draft.sort((a, b) => (a.orderTime < b.orderTime ? -1 : a.orderTime > b.orderTime ? 1 : 0))
  const limited = args.limit > 0 ? draft.slice(0, args.limit) : draft

  // 4.5 用户维度：userId 重编码 + 新老用户判定（首次购买 = new）
  const userIdByCustomer = new Map()
  const firstOrderDateByUser = new Map()
  limited.forEach((row) => {
    if (!firstOrderDateByUser.has(row.customerUniqueId)) {
      firstOrderDateByUser.set(row.customerUniqueId, row.orderTime.slice(0, 10))
    }
  })

  // 每日订单量 -> 序号位宽（真实 Olist 单日可达 1000+ 单，位宽按当日量自适应，
  // 既保持 ORD+yyyyMMdd+序号 的可读性，又保证全局唯一）
  const countByDay = new Map()
  limited.forEach((row) => {
    const day = row.orderTime.slice(0, 10)
    countByDay.set(day, (countByDay.get(day) || 0) + 1)
  })
  const widthByDay = new Map()
  countByDay.forEach((count, day) => widthByDay.set(day, Math.max(3, String(count).length)))
  const seqByDay = new Map()

  let userSeq = 0
  const finalRows = limited.map((row) => {
    if (!userIdByCustomer.has(row.customerUniqueId)) {
      // U10001 起编，与接口文档示例一致
      userIdByCustomer.set(row.customerUniqueId, `U${10001 + userSeq++}`)
    }
    const userId = userIdByCustomer.get(row.customerUniqueId)
    const isNew = firstOrderDateByUser.get(row.customerUniqueId) === row.orderTime.slice(0, 10)
    const day = row.orderTime.slice(0, 10)
    const seq = (seqByDay.get(day) || 0) + 1
    seqByDay.set(day, seq)
    // orderId 重编码为 ORD + yyyyMMdd + 当日序号，符合文档示例格式且全局唯一
    const orderId = `ORD${day.replace(/-/g, '')}${String(seq).padStart(widthByDay.get(day), '0')}`
    return {
      orderId,
      productName: row.productName,
      category: row.category,
      price: row.price,
      quantity: row.quantity,
      totalAmount: row.totalAmount,
      orderTime: row.orderTime,
      userId,
      userType: isNew ? 'new' : 'old',
      payType: row.payType,
      promoFlag: row.promoFlag,
      region: args.regionMode === 'cn' ? (BR_TO_CN_REGION[row.regionBr] || '其他') : row.regionBr,
      paymentTypeRaw: row.paymentTypeRaw
    }
  })

  // 4.6 orderId 唯一性兜底（极端情况下同日序号冲突时追加后缀）
  const seen = new Set()
  finalRows.forEach((row) => {
    let id = row.orderId
    let suffix = 0
    while (seen.has(id)) {
      suffix++
      id = `${row.orderId}X${suffix}`
    }
    seen.add(id)
    row.orderId = id
  })

  if (finalRows.length === 0) {
    console.error('没有生成任何订单，请检查 --statuses / --from / --to 过滤条件')
    process.exit(1)
  }

  // 4.7 销售目标（Olist 无目标数据）：按「月 × 品类」用实际达成额反推目标，
  //     使达成率落在 75%~115%，形成「设定→追踪→缺口」的可演示闭环
  const targetRows = buildTargets(finalRows)

  // 4.8 输出
  fs.mkdirSync(outDir, { recursive: true })
  const csvPath = path.join(outDir, 'orders.csv')
  const sqlPath = path.join(outDir, 'init-data.sql')
  const reportPath = path.join(outDir, 'data-quality-report.md')

  writeOrdersCsv(csvPath, finalRows, args)
  writeSql(sqlPath, finalRows, targetRows, args)
  writeReport(reportPath, {
    args, finalRows, targetRows,
    source: { rawOrders: rawOrders.length, rawItems: rawItems.length, rawPayments: rawPayments.length, rawCustomers: rawCustomers.length },
    skipped: { skippedStatus, skippedNoItem, skippedDate }
  })

  // 4.9 控制台摘要
  log('')
  log(`✅ 生成订单 ${finalRows.length} 条`)
  log(`   时间范围：${finalRows[0].orderTime} ~ ${finalRows[finalRows.length - 1].orderTime}`)
  log(`   用户数：${userIdByCustomer.size}（新用户占比 ${(finalRows.filter((r) => r.userType === 'new').length / finalRows.length * 100).toFixed(2)}%）`)
  log(`   品类分布：${topEntries(countBy(finalRows, 'category'), 6).map(([k, v]) => `${k}:${v}`).join('  ')}`)
  log(`   促销单占比：${(finalRows.filter((r) => r.promoFlag === 1).length / finalRows.length * 100).toFixed(2)}%`)
  log(`   目标记录：${targetRows.length} 条（${targetRows.length / 1} 行 sales_target）`)
  log(`   跳过：状态不符 ${skippedStatus} / 无明细 ${skippedNoItem} / 日期过滤 ${skippedDate}`)
  log('')
  log(`📄 ${csvPath}`)
  log(`📄 ${sqlPath}`)
  log(`📄 ${reportPath}`)
}

function log(message) { console.log(message) }

/** 按月 × 品类生成销售目标 */
function buildTargets(rows) {
  const achieved = new Map() // key: periodValue|category
  rows.forEach((row) => {
    const month = row.orderTime.slice(0, 7)
    achieved.set(`${month}|ALL`, round2((achieved.get(`${month}|ALL`) || 0) + row.totalAmount))
    achieved.set(`${month}|${row.category}`, round2((achieved.get(`${month}|${row.category}`) || 0) + row.totalAmount))
  })
  const targets = []
  let id = 1
  ;[...achieved.keys()].sort().forEach((key) => {
    const [periodValue, category] = key.split('|')
    const actual = achieved.get(key)
    if (actual <= 0) return
    // 确定性因子：同一 (月份, 品类) 每次运行结果一致
    const factor = hashFactor(`${periodValue}|${category}`, 0.75, 1.15)
    targets.push({ id: id++, periodType: 'month', periodValue, category, targetAmount: round2(actual / factor) })
  })
  return targets
}

function countBy(rows, key) {
  const map = new Map()
  rows.forEach((row) => map.set(row[key], (map.get(row[key]) || 0) + 1))
  return map
}

function topEntries(map, limit) {
  return [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, limit)
}

function writeOrdersCsv(file, rows, args) {
  const header = ['订单号', '商品名', '品类', '单价', '数量', '总金额', '下单时间', '用户ID', '用户类型', '支付方式']
  const lines = rows.map((row) =>
    [row.orderId, row.productName, row.category, row.price.toFixed(2), row.quantity, row.totalAmount.toFixed(2), row.orderTime, row.userId, row.userType, row.payType]
      .map(csvField)
      .join(',')
  )
  const content = (args.header ? [header.join(','), ...lines] : lines).join('\n') + '\n'
  fs.writeFileSync(file, (args.bom ? '\uFEFF' : '') + content, 'utf8')
}

function writeSql(file, orderRows, targetRows, args) {
  const chunks = []
  chunks.push('-- 由 tools/olist-etl.mjs 自动生成，请勿手工修改')
  chunks.push(`-- 生成时间：${new Date().toISOString()}`)
  chunks.push(`-- 订单 ${orderRows.length} 条 / 销售目标 ${targetRows.length} 条`)
  chunks.push('SET NAMES utf8mb4;')
  chunks.push('')

  if (args.ddl) {
    chunks.push(`CREATE TABLE IF NOT EXISTS orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL UNIQUE COMMENT '订单号',
  product_name VARCHAR(128) NOT NULL COMMENT '商品名称',
  category VARCHAR(32) NOT NULL COMMENT '品类',
  price DECIMAL(12,2) NOT NULL COMMENT '单价',
  quantity INT NOT NULL COMMENT '数量',
  total_amount DECIMAL(14,2) NOT NULL COMMENT '订单总金额',
  order_time DATETIME NOT NULL COMMENT '下单时间',
  user_id VARCHAR(32) NOT NULL COMMENT '用户ID',
  user_type VARCHAR(8) NOT NULL COMMENT '用户类型 new/old',
  pay_type VARCHAR(16) NOT NULL COMMENT '支付方式 alipay/wechat/card',
  promo_flag TINYINT NOT NULL DEFAULT 0 COMMENT '是否促销单 0/1',
  region VARCHAR(32) DEFAULT NULL COMMENT '收货省份',
  KEY idx_order_time (order_time),
  KEY idx_category (category),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS sales_target (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  period_type VARCHAR(16) NOT NULL COMMENT '周期类型 month',
  period_value VARCHAR(16) NOT NULL COMMENT '周期值 yyyy-MM',
  category VARCHAR(32) NOT NULL COMMENT '品类，ALL 表示总目标',
  target_amount DECIMAL(14,2) NOT NULL COMMENT '目标销售额',
  UNIQUE KEY uk_period_category (period_type, period_value, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售目标表';
`)
  }

  chunks.push('-- 订单数据')
  for (let i = 0; i < orderRows.length; i += 500) {
    const batch = orderRows.slice(i, i + 500)
    chunks.push('INSERT INTO orders (order_id, product_name, category, price, quantity, total_amount, order_time, user_id, user_type, pay_type, promo_flag, region) VALUES')
    chunks.push(
      batch
        .map((row) =>
          `('${escapeSql(row.orderId)}', '${escapeSql(row.productName)}', '${escapeSql(row.category)}', ${row.price.toFixed(2)}, ${row.quantity}, ${row.totalAmount.toFixed(2)}, '${row.orderTime}', '${row.userId}', '${row.userType}', '${row.payType}', ${row.promoFlag}, '${escapeSql(row.region)}')`
        )
        .join(',\n') + ';'
    )
    chunks.push('')
  }

  chunks.push('-- 销售目标（由实际达成额反推，达成率落在 75%~115%）')
  for (let i = 0; i < targetRows.length; i += 200) {
    const batch = targetRows.slice(i, i + 200)
    chunks.push('INSERT INTO sales_target (period_type, period_value, category, target_amount) VALUES')
    chunks.push(
      batch
        .map((row) => `('${row.periodType}', '${row.periodValue}', '${escapeSql(row.category)}', ${row.targetAmount.toFixed(2)})`)
        .join(',\n') + ';'
    )
    chunks.push('')
  }

  fs.writeFileSync(file, chunks.join('\n'), 'utf8')
}

function escapeSql(value) {
  return String(value ?? '').replace(/'/g, "''").replace(/\\/g, '\\\\')
}

function writeReport(file, context) {
  const { args, finalRows, targetRows, source, skipped } = context
  const totalSales = round2(finalRows.reduce((sum, row) => sum + row.totalAmount, 0))
  const users = new Set(finalRows.map((row) => row.userId))
  const orderCountByUser = countBy(finalRows, 'userId')
  const repeatUsers = [...orderCountByUser.values()].filter((count) => count > 1).length
  const newOrders = finalRows.filter((row) => row.userType === 'new').length
  const promoOrders = finalRows.filter((row) => row.promoFlag === 1).length
  const priceOutliers = finalRows.filter((row) => row.price < PRICE_SANITY.min || row.price > PRICE_SANITY.max).length

  const table = (title, map, options = {}) => {
    const rows = topEntries(map, 15)
    const sum = [...map.values()].reduce((total, value) => total + value, 0)
    return [
      `### ${title}`,
      '',
      `| 维度 | ${options.money ? '金额(元)' : '数量'} | 占比 |`,
      '|---|---|---|',
      ...rows.map(([key, value]) =>
        `| ${key} | ${options.money ? value.toFixed(2) : value} | ${sum ? ((value / sum) * 100).toFixed(2) + '%' : '-'} |`
      ),
      ''
    ].join('\n')
  }

  const salesByCategory = new Map()
  finalRows.forEach((row) => salesByCategory.set(row.category, round2((salesByCategory.get(row.category) || 0) + row.totalAmount)))

  // 支付方式：原始值 -> 目标枚举 的映射分布（便于答辩时说明投影关系）
  const payTypeRawMap = countBy(finalRows, 'paymentTypeRaw')
  const payTypeMappedMap = countBy(finalRows, 'payType')
  const payTypeCrossRows = [...payTypeRawMap.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([raw, count]) => `| ${raw} | ${PAYTYPE_MAP[raw] || 'card（兜底）'} | ${count} |`)
    .join('\n')

  const content = `# Olist -> 项目订单模型 数据质量报告

> 由 \`tools/olist-etl.mjs\` 自动生成：${new Date().toISOString()}

## 一、数据来源与规模

| 项目 | 值 |
|---|---|
| 数据来源 | Kaggle Brazilian E-Commerce Public Dataset by Olist（CC BY-NC-SA 4.0，仅用于非商业作品集，需署名） |
| 原始订单 | ${source.rawOrders} |
| 原始订单明细 | ${source.rawItems} |
| 原始支付记录 | ${source.rawPayments} |
| 原始客户 | ${source.rawCustomers} |
| **生成订单** | **${finalRows.length}** |
| 时间范围 | ${finalRows[0].orderTime} ~ ${finalRows[finalRows.length - 1].orderTime} |
| 总销售额 | ￥${totalSales.toFixed(2)} |
| 平均客单价 | ￥${(totalSales / finalRows.length).toFixed(2)} |
| 用户数 | ${users.size} |
| 复购用户数 / 复购率 | ${repeatUsers} / ${((repeatUsers / users.size) * 100).toFixed(2)}% |
| 新用户订单占比 | ${((newOrders / finalRows.length) * 100).toFixed(2)}% |
| 促销单占比 | ${((promoOrders / finalRows.length) * 100).toFixed(2)}% |
| 过滤统计 | 状态不符 ${skipped.skippedStatus} / 无明细 ${skipped.skippedNoItem} / 日期过滤 ${skipped.skippedDate} |

## 二、映射口径（答辩时按此说明，避免口径不清）

| 目标字段 | 来源/规则 |
|---|---|
| orderId | 重编码为 \`ORD + yyyyMMdd + 序号\`，全局唯一（原始 order_id 为 32 位十六进制，不便于阅读与演示） |
| productName | Olist **无商品名称字段**，用「品类 + 商品ID 前 6 位」构造，保证同名商品可区分 |
| category | 英文品类归并到文档示例的 6 大类（手机/电脑/服饰/食品/家电/美妆），未覆盖的落入「其他」；可用 \`--category-mode original\` 保留英文原名 |
| price | 取该订单明细的**加权均价** \`sum(price)/件数\`，并按汇率换算为人民币（当前 --rate=${args.rate}） |
| quantity | 该订单的订单明细行数（Olist 每行一件） |
| totalAmount | **重算** = 单价 × 数量，确保满足 A18 校验规则「总金额与单价×数量一致（容差 0.01）」；因此**不含运费** |
| orderTime | 原始 order_purchase_timestamp（本身就是 yyyy-MM-dd HH:mm:ss） |
| userId | 由 customer_unique_id 顺序重编码为 U10001…（保留同一自然人的多次购买，复购率/RFM 才成立） |
| userType | 该用户**首次购买日**当天的订单记为 new，之后记为 old |
| payType | credit_card/debit_card→card，boleto→alipay，voucher→wechat（Olist 支付方式向文档枚举投影，原始值在报告中可追溯） |
| promoFlag | 规则模式 --promo-rule=${args.promoRule}（br：黑五 11/23-11/30 + 圣诞周 + 周末；cn：618 + 双11 + 周末） |
| region | 模式 --region-mode=${args.regionMode}（br：巴西州码，真实；cn：映射为中国省份，属**演示映射**，用于点亮 A17 中国地图） |
| sales_target | Olist **无目标数据**，按「月 × 品类」用实际达成额 ÷ 确定性因子（0.75~1.15）反推，使 A13/A14 达成率可演示 |

## 三、过滤规则

- 保留订单状态：${args.statusList.join(', ')}（canceled / unavailable 视为无效订单）
- 日期过滤：${args.from || '不限'} ~ ${args.to || '不限'}

## 四、分布校验

${table('品类分布（订单数）', countBy(finalRows, 'category'))}
${table('品类销售额', salesByCategory, { money: true })}
${table('支付方式分布（映射后）', payTypeMappedMap)}
${table('地域分布（Top15）', countBy(finalRows, 'region'))}
${table('用户类型分布', countBy(finalRows, 'userType'))}

### 支付方式映射明细（原始 -> 目标枚举）

| Olist payment_type | 项目 payType | 订单数 |
|---|---|---|
${payTypeCrossRows}


## 五、完整性检查

| 检查项 | 结果 |
|---|---|
| orderId 唯一 | ${new Set(finalRows.map((r) => r.orderId)).size === finalRows.length ? '✅ 通过' : '❌ 存在重复'} |
| 总金额 = 单价 × 数量（容差 0.01） | ${finalRows.every((r) => Math.abs(r.price * r.quantity - r.totalAmount) <= 0.01) ? '✅ 通过' : '❌ 不通过'} |
| 支付方式枚举合法 | ${finalRows.every((r) => ['alipay', 'wechat', 'card'].includes(r.payType)) ? '✅ 通过' : '❌ 不通过'} |
| 用户类型枚举合法 | ${finalRows.every((r) => ['new', 'old'].includes(r.userType)) ? '✅ 通过' : '❌ 不通过'} |
| 下单时间格式 yyyy-MM-dd HH:mm:ss | ${finalRows.every((r) => /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(r.orderTime)) ? '✅ 通过' : '❌ 不通过'} |
| 价格异常（超出 ${PRICE_SANITY.min}~${PRICE_SANITY.max}） | ${priceOutliers === 0 ? '✅ 无' : `⚠️ ${priceOutliers} 条`} |
| 覆盖同比所需 ≥1 年数据 | ${(new Date(finalRows[finalRows.length - 1].orderTime).getTime() - new Date(finalRows[0].orderTime).getTime()) > 365 * 24 * 3600 * 1000 ? '✅ 满足（A7 同比可用）' : '⚠️ 不满足，A7 同比将返回 null'} |

## 六、产出文件

| 文件 | 用途 |
|---|---|
| \`orders.csv\` | 通过 **A18 CSV 批量导入**接口灌入后端（列顺序与数据字典一致） |
| \`init-data.sql\` | 直接初始化数据库（orders + sales_target），可配合 Spring Boot \`spring.sql.init\` 使用 |

## 七、使用注意

1. 本数据集为 **CC BY-NC-SA 4.0**，作品集/课程演示可用，**不得商用**，公开展示时请标注来源（Olist / Kaggle）。
2. \`--rate\` 为近似汇率，答辩时应说明「巴西雷亚尔按 ${args.rate} 近似换算为人民币」；也可用 \`--rate 1\` 保持原始数值。
3. 品类归并与地域映射属于**投影**，不是原始事实；报告中已明确标注，避免答辩被追问时口径不清。
`

  fs.writeFileSync(file, content, 'utf8')
}

main()
