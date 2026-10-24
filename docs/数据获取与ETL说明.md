# 数据来源与 ETL 说明（Olist → 项目订单模型）

> 适用项目：电商销售数据洞察看板
> 相关文件：`tools/olist-etl.mjs`（ETL 脚本）、`tools/fixtures/`（字段结构与 Olist 一致的干跑样本）、`data/olist/`（原始数据放置目录）

---

## 一、为什么选 Olist

对比过的候选数据集：

| 数据集 | 字段覆盖 | 结论 |
|---|---|---|
| **Olist 巴西电商（≈10 万订单，2016-09 ~ 2018-10）** | 订单状态/下单时间、订单明细（单价、件数）、商品品类、支付方式、客户唯一 ID、客户州 | ✅ **采用**：唯一能同时支撑「金额类指标 + 复购/新老客 + RFM + 地域 + 支付方式」的公开数据集，时间跨度 >2 年，A7 同比有数据 |
| UCI Online Retail II（英国零售 100 万行） | 金额、时间、客户、国家 | ❌ 无品类、无支付方式、无省份，且有负数退货行 |
| 阿里天池 淘宝用户行为（1 亿条） | 只有行为类型与时间戳 | ❌ 完全没有金额与支付方式，无法支撑 KPI/客单价/复购 |

⚠️ 但**没有任何公开数据集能直接满足本项目接口文档的字段规范**（品类枚举、支付方式枚举、用户类型、中国省份、促销标记、销售目标表），所以必须做一层 ETL 映射——这也是本项目「数据工程」部分的实际工作量。

## 二、使用步骤

```bash
# 1) 下载并解压 Olist CSV 到 data/olist/（详见 data/olist/README.md）
# 2) 执行 ETL
node tools/olist-etl.mjs --source data/olist --out data/out

# 干跑（无数据集时先用自带样本验证流程）
node tools/olist-etl.mjs --source tools/fixtures --out .tmp-etl-out
```

产出（`data/out/`）：

| 文件 | 用途 |
|---|---|
| `orders.csv` | 列顺序 = `订单号,商品名,品类,单价,数量,总金额,下单时间,用户ID,用户类型,支付方式`，**可直接用看板右上角「CSV 批量导入」走 A18/A19 接口灌数据**（演示异步任务 + 错误报告的最佳素材） |
| `init-data.sql` | `orders` + `sales_target` 的 INSERT（`--ddl` 时附带建表语句），可直接初始化 MySQL |
| `data-quality-report.md` | 数据规模、分布、完整性检查结果——**写进答辩 PPT / 项目文档的现成材料** |

## 三、常用参数

| 参数 | 说明 |
|---|---|
| `--from / --to` | 日期区间过滤，例如 `--from 2017-01-01`（Olist 的 2016 年只有 9-12 月，数据稀疏，通常裁掉） |
| `--rate` | BRL→CNY 汇率，默认 `1.45`；`--rate 1` 表示不换算，保持原始数值 |
| `--region-mode br\|cn` | `br`（默认）保留巴西州码（真实）；`cn` 映射为中国省份（**演示映射**，用于点亮 A17 中国地图）；前端在地图匹配不到时会自动降级成排行榜柱状图，所以两种模式都能正常展示 |
| `--promo-rule br\|cn` | `br`（默认）黑五 11/23-11/30 + 圣诞周 + 周末；`cn` 618 + 双11 + 周末 |
| `--category-mode doc6\|original` | `doc6`（默认）归并到文档示例的 6 大类；`original` 保留英文原名（品类更多，看板筛选下拉会变长） |
| `--statuses` | 保留的订单状态，默认 `delivered,shipped,invoiced,processing,approved`（排除 canceled / unavailable） |
| `--limit` | 只处理前 n 单，调试用 |
| `--no-header` | `orders.csv` 不输出表头（后端解析按列顺序、且不跳表头时用） |
| `--bom` | `orders.csv` 加 UTF-8 BOM（Excel 打开中文不乱码；**导入后端不要加**，否则第一列会带 BOM） |
| `--ddl` | SQL 中附带 `CREATE TABLE IF NOT EXISTS` |

## 四、字段映射口径（答辩必讲，避免被追问）

| 目标字段 | 规则 | 说明 |
|---|---|---|
| `orderId` | `ORD` + `yyyyMMdd` + 当日序号（位宽按当日单量自适应，最少 3 位） | 原始 `order_id` 是 32 位十六进制，不适合演示；重编码后全局唯一且可读 |
| `productName` | `品类 + 商品ID前6位`，如 `手机 8F3A2B` | **Olist 没有商品名称字段**，只能构造；已在报告中声明 |
| `category` | 葡文 → 英文 → 文档 6 大类归并 | 归并表在脚本顶部 `CATEGORY_MAP`，可直接改；覆盖不到的落「其他」 |
| `price` | 订单明细加权均价 `sum(price)/件数` × 汇率，四舍五入 2 位 | 一个订单多件不同价时取均价，保留订单价值量级 |
| `quantity` | 订单明细行数 | Olist 每行一件 |
| `totalAmount` | **重算** `单价 × 数量` | 为保证 A18 校验规则「总金额与单价×数量一致（容差 0.01）」；**因此不含运费** |
| `orderTime` | 原始 `order_purchase_timestamp` | 本身就是 `yyyy-MM-dd HH:mm:ss`，无需转换 |
| `userId` | `customer_unique_id` 顺序重编码为 `U10001…` | 用 unique_id（自然人）而非 customer_id（每单一个），**复购率/RFM 才成立** |
| `userType` | 首次购买日当天的订单 = `new`，之后 = `old` | 与 A11「新老用户对比」口径自洽 |
| `payType` | credit_card/debit_card→`card`，boleto→`alipay`，voucher→`wechat` | 向文档枚举投影；报告中给出映射明细表，保留可追溯性 |
| `promoFlag` | 大促区间 + 周末 = 1 | 见 `--promo-rule` |
| `region` | `customer_state`（或 `--region-mode cn` 映射中国省份） | A17 用 |
| `sales_target` | 按「月 × 品类」用实际达成额 ÷ 确定性因子（0.75~1.15）反推 | **Olist 无目标数据**；因子由字符串哈希生成，保证重复执行结果一致，达成率落在 75%~115%，A13/A14 才有演示效果 |

## 五、完整性校验（脚本自动写入报告）

ETL 每次运行都会在报告中输出以下检查结果：

| 检查项 | 结果 |
|---|---|
| `orderId` 唯一 | ✅ |
| `总金额 = 单价 × 数量`（容差 0.01） | ✅ |
| 支付方式 / 用户类型枚举合法 | ✅ |
| 下单时间格式 `yyyy-MM-dd HH:mm:ss` | ✅ |
| 价格异常值（<0.01 或 >200000） | ✅ 无 |
| 是否覆盖 ≥1 年数据（A7 同比前提） | ✅（Olist 原生跨 2016-09~2018-10） |

已用 `tools/fixtures/`（15 单：含多件订单、重复购买客户、已取消/不可用订单、缺失品类、缺失支付记录）跑通全流程，13 条有效订单全部通过上述校验，`orders.csv` 与 `init-data.sql` 的订单元组数一致（13 = 13），`sales_target` 25 条。

## 六、把数据灌进后端的两条路径

1. **走界面（推荐演示）**：启动后端 → 打开看板 → 右上角「CSV 批量导入」→ 选 `data/out/orders.csv` → 选择「覆盖导入」→ 观察 A19 轮询进度与导入报告。面试可讲异步任务、逐行校验、错误行报告、幂等控制。
2. **走 SQL（初始化/回滚方便）**：`mysql -u root -p ecommerce < data/out/init-data.sql`，或放到 Spring Boot `src/main/resources/` 配 `spring.sql.init.mode=always`（`--ddl` 生成的建表语句可直接用）。

> 包体量大：Olist 全量约 10 万订单，导入 5000+ 条即可满足文档「不少于 1000 条、建议 5000+」的要求；如需裁剪可用 `--from/--to` 或 `--limit`。

## 七、合规与署名

Olist 数据集为 **CC BY-NC-SA 4.0**：非商业用途可用（课程、作品集），需署名并同协议共享，**不得商用**。公开作品请标注：

> Data source: Brazilian E-Commerce Public Dataset by Olist (Kaggle), CC BY-NC-SA 4.0

另外两点口径提醒（答辩时主动说明，比被追问更好）：
1. `productName`、`sales_target`、品类归并、地域映射都属于**派生/构造数据**，不是原始事实；
2. 汇率换算是近似值（`--rate`），如需严谨可保持原币种（`--rate 1`）。
