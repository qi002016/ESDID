# 电商销售数据洞察看板 — 后端开发计划

> 依据：《后端开发文档.md》V1.0（19 个接口 A1~A19）、《数据获取与ETL说明.md》、《电商销售数据洞察看板-前端需求文档.md》、《大屏设计规范.md》
> 开发目录：`D:\ESDID\backend\springboot-ES`（沿用现有 Maven 工程，包根 `com.heima`）

---

## 一、工程现状盘点（动手前）

| 项 | 现状 | 处理方式 |
|---|---|---|
| 构建 | Maven，Spring Boot **4.0.8**，JDK **25**，`com.heima` 包根 | **沿用**（文档写 JDK17+Boot3.2，以工程现状为准，差异写入交付说明） |
| 持久层 | MyBatis-Plus 3.5.17 + `mybatis-spring-boot-starter`，已有 XML Mapper | 沿用；聚合统计走 XML 原生 SQL，CRUD 走 `BaseMapper` |
| 已有依赖 | spring-webmvc、MP、MySQL、Redis、knife4j(springdoc)、hutool、pagehelper、poi、jjwt、lombok | 沿用；新增 **Caffeine**（文档 §1.1 指定，用于 A1/A13 缓存） |
| 已有脚手架 | `Result`(code=1 成功、字段 `msg`)、`ErrorCode`、`PageResult`(裸 List)、`BizException`、`GlobalExceptionHandler`、`Orders/SalesTarget` 实体、部分 Mapper/XML、A1/A2/A3/A4/A5/A6 雏形 | **按文档契约改造**：成功码统一 `code=0`、字段统一 `message`、分页结果泛型化 |
| 待修复问题 | `CategoryServiceImpl` 用 `StringRedisTemplate` 存 `List.toString()` 后强转 `List`（必然 `ClassCastException`）；A2/A4/A5 用 POST 而文档/前端是 GET；A6 用 MP 主键查订单；A5 week 分支 `YEARWEEK-WEEK` 与文档 ISO 周不一致；A3 复购率/新客率口径与文档不一致 | 全部按文档重写 |
| 数据库 | MySQL 8.0（已运行）、Redis（已运行）；已有空库 `ecommerce` | 新建库 `esd_dashboard`（文档命名），用 `data/out/init-data.sql` 灌数 |
| 数据集 | `data/out/orders.csv`（44375 单，含中文省份）、`data/out/init-data.sql`（orders + sales_target） | 作为初始化与 A18 导入演示素材 |

## 二、模块划分（延续现有分层）

```
com.heima
├─ common/      Result(code/message/data) · ErrorCode · PageResult<T> · 统一错误码常量
├─ exception/   BizException · GlobalExceptionHandler（400/404/409/500 全覆盖）
├─ config/      CorsConfig · OpenApiConfig(knife4j 分组) · JacksonConfig · AsyncConfig · CacheConfig(Caffeine)
├─ controller/  CategoryController(A1) · OrdersController(A2/A6/A12/A18/A19)
│               OrderStatsController(A3/A4/A5/A7/A8/A9/A10/A11/A14/A15/A17)
│               SalesTargetController(A13) · UserAnalysisController(A16)
├─ domain/
│  ├─ dto/      OrderQueryDTO · StatsQueryDTO · TrendQueryDTO · RankingQueryDTO · TargetQueryDTO · RfmQueryDTO · CampaignQueryDTO
│  ├─ po/       Orders · SalesTarget · ImportTask · ImportTaskError · DailySummary
│  ─ vo/       CategoryVO · KpiVO · OrderVO · RankingsVO · PeriodItemVO · TrendItemVO · PriceIntervalVO
│               CategoryContributionVO · HourlyVO · UserAnalysisVO · PayTypeRateVO · RfmVO · RfmSegmentVO
│               RegionVO · TargetVO · TargetProgressVO · CampaignEffectVO · ImportTaskVO · ImportErrorVO
├─ mapper/      OrdersMapper · SalesTargetMapper · ImportTaskMapper · ImportTaskErrorMapper · DailySummaryMapper
├─ service/     ICategoryService · IOrdersService · IOrderStatsService · ISalesTargetService · IOrderImportService · IRfmService
├─ task/        ImportTaskExecutor（@Async 逐行校验+批量写入） · DailySummaryJob（P1 预聚合）
└─ util/        DateRangeUtils · SqlSortWhitelist · Granularity · CsvUtils · NumberUtils
resources/
├─ application.yaml / application-dev.yml
├─ mapper/*.xml                聚合 SQL
└─ db/schema.sql               建表（文档 §3.1 原样）
```

## 三、统一契约（实现红线）

1. 响应体 `{code,message,data}`，`code=0` 成功；业务失败 400/404/409/500，`message` 中文可读。
2. 日期参数严格 `yyyy-MM-dd`；缺省 **最近 30 天**（A16 单独为 **90 天**）；时间条件一律左闭右开 `>= start AND < end+1day`。
3. 比率字段返回 0~1 小数（4 位）；无法计算一律 `null`（不返回 0/Infinity）；`spring.jackson.default-property-inclusion: always` 保证 `null` 不被剔除。
4. 排序/粒度/枚举全部白名单化，杜绝 SQL 拼接注入（`sort`、`rankBy`、`granularity`）。
5. 空数据：数组 `[]`、数值 `0`、比率 `null`；A10 固定 24 个元素、A8 固定 6 个区间。

## 四、实现顺序与里程碑

| 里程碑 | 内容 | 接口 | 验证 |
|---|---|---|---|
| **M1 骨架** | Result/ErrorCode/异常/PageResult、CORS、Jackson、Caffeine、Async、`schema.sql`、配置补全、工具类 | — | `mvn compile` + 建库成功 |
| **M2 基础查询** | 订单实体/Mapper/XML、品类查询、分页（count+limit）、详情（404） | A1 A2 A6 | curl 冒烟 |
| **M3 统计接口** | KPI 汇总、TOP10、粒度聚合、环比同比趋势、客单价分布、帕累托、24 小时、用户分析 | A3 A4 A5 A7 A8 A9 A10 A11 | curl 冒烟 + 单测 |
| **M4 扩展接口** | 目标列表、目标达成率、促销效果、RFM、地域分布 | A13 A14 A15 A16 A17 | curl 冒烟 + 单测 |
| **M5 文件与工程化** | 流式 CSV 导出（游标分页）、异步导入 + 任务轮询（409/404）、品类缓存失效、预聚合任务、单测 | A12 A18 A19 | round-trip 导入验证 |
| **M6 整体验证** | 编译/单测/启动/19 接口冒烟/验收清单逐条核对 | 全部 | 验收清单 |
| **M7 交付** | 交付说明（功能清单、接口路径、验证结果、差异与未覆盖项） | — | 文档 |

## 五、关键口径（易错点，实现时逐条对照）

- **A3**：复购率 = 期内下单 ≥2 次用户数 / 期内总用户数；新客率同分母；`0/0` → `null`。
- **A5**：周粒度用 ISO 周 `DATE_FORMAT('%x-W%v')`，跨年不串年；空区间返回 `[]`，不补零桶。
- **A7**：本期/对比期**按桶序号对齐**；`compareType=previous` 用前等长区间，`previousYear` 用去年同期；`prev=0/缺失 → growthRate=null`。
- **A8**：6 个固定区间，缺失补 0，`orderRatio` 四位小数。
- **A9**：仅 `startDate/endDate`；窗口函数算 `salesRatio/cumulativeRatio`，Java 补 `rank`。
- **A10**：固定 24 元素。
- **A11**：`payTypeRates.rate` 分母为订单数；`newUserAvgOrderValue = 新客销售额/新客订单数`。
- **A12**：`StreamingResponseBody` + 游标 `id > lastId`，列顺序与 A18 一致（可 round-trip），带 `Content-Disposition`。
- **A13/A14**：`ALL` 总目标；`achievementRate = target>0 ? achieved/target : null`；`remainingDays` 按周期末-今天，非当前周期为 0；`dailyNeeded` 相应为 0。
- **A15**：非活动期 = 活动期前等长区间（**不是** 同期 promoFlag=0）；两个必填日期。
- **A16**：默认近 90 天；中位数二分阈值；8 类分层名称固定映射；`salesRatio` 合计 ≈ 1。
- **A17**：`region` 原样返回，不做翻译；按销售额降序 + Java 补 `rank`。
- **A18/A19**：全局或同 `file_hash` 已有 `PROCESSING` → 409；列数 ≠10 / 空文件 → 400；taskId 不存在 → 404；`errors` 最多返回 500 条。

## 六、风险与预案

| 风险 | 预案 |
|---|---|
| Boot 4 + JDK25 与文档示例注解差异 | 以现有工程可编译为准，`jakarta.*` 校验注解与 Boot4 一致；不用 Boot4 已废弃 API |
| MyBatis-Plus 分页插件在 3.5.17 需额外 jsqlparser 模块 | A2 按文档用「count + limit」手写 SQL，**不依赖分页插件** |
| 本地离线依赖 | 只新增已在本地仓库存在或可下载的 Caffeine；不引入 H2（本地无） |
| Redis 存在但文档推荐 Caffeine | 用 Caffeine 本地缓存（TTL：品类 10min、目标 5min），导入完成后主动失效 |
| 预聚合表与实时口径一致性 | 预聚合任务独立可跑，不接入统计读取路径（避免缺当日数据导致 KPI 偏小），交付说明标注 |

---

## 七、实际交付状态（回填，最终以代码与《交付说明.md》为准）

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M1 骨架 | ✅ 完成 | `Result/ErrorCode/PageResult`、异常处理、CORS、Caffeine、Async、`schema.sql`、配置齐全。**未使用 JacksonConfig**：Boot 4 的 Jackson 自动配置已改名（`JsonMapperBuilderCustomizer`），改用字段级 `@JsonFormat` + `spring.jackson.*` 配置实现同样的日期格式与 null 保留 |
| M2 基础查询 | ✅ 完成 | A1/A2/A6；A2 为「count + limit」手写 SQL，不依赖 MP 分页插件 |
| M3 统计接口 | ✅ 完成 | A3/A4/A5/A7/A8/A9/A10/A11 |
| M4 扩展接口 | ✅ 完成 | A13/A14/A15/A16/A17 |
| M5 文件与工程化 | ✅ 完成 | A12 流式导出（游标分页 + BOM）、A18/A19 异步导入（400/409/404、逐行错误明细）、缓存主动失效、`DailySummaryJob` 预聚合 |
| M6 整体验证 | ✅ 完成 | `mvn test`（单测 + 真实 MySQL 集成验证）+ 43 项 HTTP 冒烟 + 导入链路验证 |
| M7 交付 | ✅ 完成 | `DEVELOPMENT-PLAN.md`（本文件）+ `交付说明.md` |

### 开发过程中发现并修复的真实缺陷

1. **A1 500**：`OrdersMapper.countProductsByCategory` 的 `@Select` 改造为 XML 时漏写语句 → `Invalid bound statement`，已补 XML（`COUNT(DISTINCT product_name)`）。
2. **CORS 占位符无法解析**：文档 §8.2 示例用 `@Value("${dashboard.cors.allowed-origins}")` 注入 YAML **列表**，Boot 直接抛 `PlaceholderResolutionException` 启动失败 → 改为 `@ConfigurationProperties(prefix="dashboard.cors")`。
3. **导入状态机与文档不符**：`validRows=0 且 errorRows=0`（例如只有表头的文件）被判为 `SUCCESS`，文档 §6.1 要求 `FAILED` → 已修正。
4. **覆盖导入的丢数据风险**：「先清空、再逐行校验」时若整个文件无效，会把原数据清空却以 FAILED 收尾 → 改为「出现第一行有效数据时才清空」，同事务提交。
5. **缺文件 part 返回 500**：`@RequestParam MultipartFile` 缺失抛 `MissingServletRequestPartException`/`MultipartException`，落到兜底 500 → 新增处理器统一返回 400「文件为空」。
6. **Jackson 3（Boot 4）序列化**：Boot 4.0.8 使用 `tools.jackson`（Jackson 3），Jackson 2 的 `JavaTimeModule` 定制器已失效 → 用 `@JsonFormat` 精确控制 `yyyy-MM-dd HH:mm:ss`，并用真实请求验证 `null` 字段保留。