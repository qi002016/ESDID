package com.heima;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.task.DailySummaryJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 19 个接口的真实链路集成验证（《后端开发文档》§10.2 / §10.3 的落地版本）。
 * <p>文档示例用 H2 + MockMvc；本工程依赖 MySQL 方言（{@code DATE_FORMAT}、窗口函数、{@code HOUR()}），
 * H2 兼容模式下并不等价，因此改为「真实 MySQL + 随机端口 + HttpClient」端到端校验，口径与线上完全一致。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardEndpointsTests {

    /** 数据集中订单的日期范围（data/out/orders.csv 为 2026 全年） */
    private static final String RANGE = "startDate=2026-01-01&endDate=2026-12-31";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DailySummaryJob dailySummaryJob;

    // ==================== 工具 ====================

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + pathAndQuery))
                .timeout(Duration.ofSeconds(120)).GET().build();
        HttpResponse<String> response = send(request);
        assertEquals(200, response.statusCode(), "HTTP 状态必须为 200（业务错误码走 body）：" + pathAndQuery);
        return MAPPER.readTree(response.body());
    }

    /** 断言 code=0 并返回 data */
    private JsonNode ok(String pathAndQuery) throws Exception {
        JsonNode root = getJson(pathAndQuery);
        assertEquals(0, root.path("code").asInt(), pathAndQuery + " -> " + root.path("message").asText());
        assertTrue(root.has("message"), "响应体必须含 message 字段");
        assertTrue(root.has("data"), "响应体必须含 data 字段");
        return root.path("data");
    }

    /** 断言业务错误码（HTTP 仍为 200） */
    private JsonNode fail(String pathAndQuery, int expectedCode) throws Exception {
        JsonNode root = getJson(pathAndQuery);
        assertEquals(expectedCode, root.path("code").asInt(),
                pathAndQuery + " 期望 code=" + expectedCode + "，实际=" + root.path("code").asInt());
        return root;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("A1~A17 JSON 接口全部返回 code=0 且结构完整")
    void allJsonEndpointsReturnCodeZero() throws Exception {
        JsonNode categories = ok("/api/categories");
        assertTrue(categories.isArray());
        assertTrue(categories.size() > 0, "orders 表已灌数，品类不应为空");
        assertTrue(categories.get(0).has("category"));
        assertTrue(categories.get(0).has("productCount"));

        JsonNode page = ok("/api/orders?page=1&size=5&sort=totalAmount,desc&" + RANGE);
        assertEquals(5, page.path("records").size());
        assertEquals(1, page.path("page").asInt());
        assertEquals(5, page.path("size").asInt());
        assertTrue(page.path("total").asLong() > 0);
        JsonNode order = page.path("records").get(0);
        for (String field : new String[]{"orderId", "productName", "category", "price", "quantity",
                "totalAmount", "orderTime", "userId", "userType", "payType", "promoFlag", "region"}) {
            assertTrue(order.has(field), "OrderVO 缺少字段 " + field);
        }
        // A2 排序生效：totalAmount 降序
        assertTrue(page.path("records").get(0).path("totalAmount").asDouble()
                >= page.path("records").get(4).path("totalAmount").asDouble());

        String firstOrderId = order.path("orderId").asText();
        JsonNode detail = ok("/api/orders/" + firstOrderId);
        assertEquals(firstOrderId, detail.path("orderId").asText());
        assertTrue(detail.path("orderTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "orderTime 必须序列化为 yyyy-MM-dd HH:mm:ss，实际=" + detail.path("orderTime").asText());

        JsonNode kpi = ok("/api/orders/kpi?" + RANGE);
        for (String field : new String[]{"totalSales", "totalOrders", "avgOrderValue", "hotProductCount",
                "totalUsers", "newUserRate", "repurchaseRate"}) {
            assertTrue(kpi.has(field), "KpiVO 缺少字段 " + field);
        }
        assertTrue(kpi.path("totalOrders").asLong() > 0);
        assertTrue(kpi.path("totalSales").asDouble() > 0);

        JsonNode ranking = ok("/api/orders/ranking?rankBy=sales&limit=10&" + RANGE);
        assertEquals(10, ranking.size());
        assertEquals(1, ranking.get(0).path("rank").asInt());
        assertTrue(ranking.get(0).path("sales").asDouble() >= ranking.get(9).path("sales").asDouble());

        ok("/api/orders/stats?granularity=day&" + RANGE);
        ok("/api/orders/stats?granularity=week&" + RANGE);
        ok("/api/orders/stats?granularity=month&" + RANGE);
        ok("/api/orders/trend?granularity=month&compareType=previous&" + RANGE);
        ok("/api/orders/trend?granularity=month&compareType=previousYear&" + RANGE);
        ok("/api/orders/category-contribution?" + RANGE);
        ok("/api/orders/user-analysis?" + RANGE);
        ok("/api/targets?periodType=month&periodValue=2026-06");
        ok("/api/orders/target-progress?periodType=month&periodValue=2026-06");
        ok("/api/orders/campaign-effect?startDate=2026-11-01&endDate=2026-11-11");
        ok("/api/users/rfm?" + RANGE);
        ok("/api/orders/region?" + RANGE);
    }

    @Test
    @DisplayName("A5 粒度聚合：day/week/month 分段正确，周用 ISO 周不串年")
    void a5Granularity() throws Exception {
        JsonNode day = ok("/api/orders/stats?granularity=day&startDate=2026-01-01&endDate=2026-01-10");
        assertEquals("day", day.path("granularity").asText());
        assertTrue(day.path("items").size() > 0);
        assertTrue(day.path("items").get(0).path("period").asText().matches("\\d{4}-\\d{2}-\\d{2}"));

        JsonNode week = ok("/api/orders/stats?granularity=week&startDate=2025-12-01&endDate=2026-01-31");
        for (JsonNode item : week.path("items")) {
            assertTrue(item.path("period").asText().matches("\\d{4}-W\\d{2}"),
                    "周粒度 period 形如 yyyy-Www，实际=" + item.path("period").asText());
        }
        JsonNode month = ok("/api/orders/stats?granularity=month&startDate=2026-01-01&endDate=2026-03-31");
        assertEquals("2026-01", month.path("items").get(0).path("period").asText());
        assertEquals("2026-03", month.path("items").get(2).path("period").asText());
    }

    @Test
    @DisplayName("A7 趋势对比：对比期正确、缺失对比数据为 null 而不是 0")
    void a7TrendAlignment() throws Exception {
        JsonNode trend = ok("/api/orders/trend?granularity=month&compareType=previous&startDate=2026-06-01&endDate=2026-11-30");
        assertEquals("2026-06-01", trend.path("currentPeriod").path("startDate").asText());
        assertEquals("2026-11-30", trend.path("currentPeriod").path("endDate").asText());
        // 等长前区间：183 天 -> 2025-11-30 ~ 2026-05-31
        assertEquals("2025-11-30", trend.path("comparePeriod").path("startDate").asText());
        assertEquals("2026-05-31", trend.path("comparePeriod").path("endDate").asText());
        assertEquals(6, trend.path("items").size());
        JsonNode last = trend.path("items").get(5);
        assertTrue(last.path("prevSales").isNull(), "对比期无数据时 prevSales 必须为 null（前端显示「无对比数据」）");
        assertTrue(last.path("salesGrowthRate").isNull(), "分母为 0/缺失时 growthRate 必须为 null");

        JsonNode yoy = ok("/api/orders/trend?granularity=month&compareType=previousYear&startDate=2026-06-01&endDate=2026-08-31");
        assertEquals("2025-06-01", yoy.path("comparePeriod").path("startDate").asText());
        assertEquals(3, yoy.path("items").size(), "同比数据不足时仍必须返回本期数据");
        assertTrue(yoy.path("items").get(0).path("salesGrowthRate").isNull());
        assertTrue(yoy.path("items").get(0).path("currentSales").asDouble() > 0);
    }

    @Test
    @DisplayName("A8/A9/A10/A16/A17 固定结构：6 区间、累计占比、24 小时、8 分层、地域排名")
    void fixedStructures() throws Exception {
        JsonNode price = ok("/api/orders/price-distribution?" + RANGE);
        JsonNode intervals = price.path("intervals");
        assertEquals(6, intervals.size(), "客单价区间固定 6 个");
        assertEquals("0-100", intervals.get(0).path("range").asText());
        double ratioSum = 0;
        for (JsonNode interval : intervals) {
            ratioSum += interval.path("orderRatio").asDouble();
        }
        assertTrue(Math.abs(ratioSum - 1.0) < 0.01, "orderRatio 之和应≈1，实际=" + ratioSum);

        JsonNode contribution = ok("/api/orders/category-contribution?" + RANGE);
        assertTrue(contribution.path("items").size() > 0);
        JsonNode lastCategory = contribution.path("items").get(contribution.path("items").size() - 1);
        assertEquals(1.0, lastCategory.path("cumulativeRatio").asDouble(), 0.001, "累计占比末位必须为 1");
        assertEquals(1, contribution.path("items").get(0).path("rank").asInt());

        JsonNode hourly = ok("/api/orders/hourly?" + RANGE);
        assertEquals(24, hourly.path("hours").size(), "时段分布固定 24 个元素");
        assertEquals(0, hourly.path("hours").get(0).path("hour").asInt());
        assertEquals(23, hourly.path("hours").get(23).path("hour").asInt());

        JsonNode user = ok("/api/orders/user-analysis?" + RANGE);
        assertTrue(user.path("payTypeRates").size() > 0);
        double payRateSum = 0;
        for (JsonNode rate : user.path("payTypeRates")) {
            payRateSum += rate.path("rate").asDouble();
        }
        assertTrue(Math.abs(payRateSum - 1.0) < 0.01, "payTypeRates.rate 之和应≈1，实际=" + payRateSum);

        JsonNode rfm = ok("/api/users/rfm?" + RANGE);
        assertNotNull(rfm.path("thresholds"));
        for (String field : new String[]{"rDays", "fCount", "mAmount"}) {
            assertTrue(rfm.path("thresholds").has(field), "RFM 阈值缺少 " + field);
        }
        double segmentRatioSum = 0;
        for (JsonNode segment : rfm.path("segments")) {
            segmentRatioSum += segment.path("salesRatio").asDouble();
        }
        assertTrue(Math.abs(segmentRatioSum - 1.0) < 0.01, "RFM salesRatio 之和应≈1，实际=" + segmentRatioSum);

        JsonNode region = ok("/api/orders/region?" + RANGE);
        assertTrue(region.path("items").size() > 0);
        JsonNode firstRegion = region.path("items").get(0);
        assertEquals(1, firstRegion.path("rank").asInt());
        assertTrue(firstRegion.has("region") && firstRegion.has("sales") && firstRegion.has("orderCount"));
        assertTrue(firstRegion.path("region").asText().length() > 0, "region 原样返回，不做翻译");
    }

    @Test
    @DisplayName("A13/A14：目标列表与达成率（缺失目标与已结束周期的口径）")
    void targetsAndProgress() throws Exception {
        JsonNode list = ok("/api/targets?periodType=month&periodValue=2026-06");
        assertEquals("2026-06", list.path("periodValue").asText());
        assertTrue(list.path("items").size() >= 2);
        assertEquals("ALL", list.path("items").get(0).path("category").asText(), "ALL 必须排在最前");

        JsonNode progress = ok("/api/orders/target-progress?periodType=month&periodValue=2026-06");
        assertEquals("2026-06", progress.path("periodValue").asText());
        assertTrue(progress.path("totalTarget").asDouble() > 0);
        assertTrue(progress.path("achievedSales").asDouble() > 0);
        // 历史周期：剩余天数为 0，日均需完成 0
        assertEquals(0, progress.path("remainingDays").asInt());
        assertEquals(0.0, progress.path("dailyNeeded").asDouble(), 0.001);
        assertTrue(progress.path("items").size() >= 2);
        boolean hasAll = false;
        for (JsonNode item : progress.path("items")) {
            if ("ALL".equals(item.path("category").asText())) {
                hasAll = true;
            }
        }
        assertTrue(hasAll, "A14 items 必须包含 ALL");

        // 目标缺失：achievementRate = null，不报错
        JsonNode missing = ok("/api/orders/target-progress?periodType=month&periodValue=1999-01");
        assertTrue(missing.path("achievementRate").isNull(), "目标缺失时 achievementRate 必须为 null");
        assertEquals(0.0, missing.path("totalTarget").asDouble(), 0.001);
    }

    @Test
    @DisplayName("A15 促销效果：非活动期=活动期前等长区间；未来区间返回 0 而不是报错")
    void campaignEffect() throws Exception {
        JsonNode effect = ok("/api/orders/campaign-effect?startDate=2026-11-01&endDate=2026-11-11");
        assertEquals("2026-11-01", effect.path("activityPeriodStart").asText());
        assertEquals("2026-11-11", effect.path("activityPeriodEnd").asText());
        assertEquals("2026-10-21", effect.path("nonActivityPeriodStart").asText(), "非活动期 = 活动期前等长区间");
        assertEquals("2026-10-31", effect.path("nonActivityPeriodEnd").asText());
        assertTrue(effect.path("activity").path("orders").asLong() > 0);
        assertTrue(effect.has("salesGrowthRate"));
        assertTrue(effect.has("promotionPullRate"));

        JsonNode future = ok("/api/orders/campaign-effect?startDate=2099-01-01&endDate=2099-01-11");
        assertEquals(0.0, future.path("activity").path("sales").asDouble(), 0.001);
        assertEquals(0L, future.path("activity").path("orders").asLong());
        assertTrue(future.path("salesGrowthRate").isNull(), "两个区间都为 0 时增长率必须为 null");
    }

    @Test
    @DisplayName("异常路径：400（page/size/sort/枚举/日期）与 404（订单、taskId）")
    void errorPaths() throws Exception {
        fail("/api/orders?page=0&" + RANGE, 400);
        fail("/api/orders?size=101&" + RANGE, 400);
        fail("/api/orders?sort=1;drop&" + RANGE, 400);
        fail("/api/orders?sort=unknown,desc&" + RANGE, 400);
        fail("/api/orders?promoFlag=5&" + RANGE, 400);
        fail("/api/orders?payType=paypal&" + RANGE, 400);
        fail("/api/orders/kpi?startDate=2017-13-01&endDate=2017-01-31", 400);
        fail("/api/orders/kpi?startDate=2017-05-01&endDate=2017-01-31", 400);
        fail("/api/orders/stats?granularity=hour", 400);
        fail("/api/orders/trend?granularity=hour", 400);
        fail("/api/orders/trend?compareType=unknown", 400);
        fail("/api/orders/ranking?rankBy=unknown", 400);
        fail("/api/orders/ranking?limit=51", 400);
        fail("/api/orders/campaign-effect", 400);
        fail("/api/orders/NOT_EXIST", 404);
        fail("/api/orders/import/tasks/not_exists_task", 404);
    }

    @Test
    @DisplayName("空数据区间：数组为空、数值为 0、比率为 null（不报 404/500）")
    void emptyRange() throws Exception {
        String empty = "startDate=2000-01-01&endDate=2000-01-31";
        JsonNode kpi = ok("/api/orders/kpi?" + empty);
        assertEquals(0L, kpi.path("totalOrders").asLong());
        assertEquals(0.0, kpi.path("totalSales").asDouble(), 0.001);
        assertTrue(kpi.path("newUserRate").isNull());
        assertTrue(kpi.path("repurchaseRate").isNull());

        assertEquals(0, ok("/api/orders/category-contribution?" + empty).path("items").size());
        assertEquals(0, ok("/api/users/rfm?" + empty).path("segments").size());
        assertEquals(0, ok("/api/orders/region?" + empty).path("items").size());
        assertEquals(6, ok("/api/orders/price-distribution?" + empty).path("intervals").size());
        assertEquals(24, ok("/api/orders/hourly?" + empty).path("hours").size());
        assertEquals(0, ok("/api/orders/ranking?" + empty).size());
    }

    @Test
    @DisplayName("A12 导出：Content-Disposition、UTF-8 BOM、10 列（可被 A18 反向导入）")
    void exportCsv() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base()
                        + "/api/orders/export?startDate=2026-01-01&endDate=2026-01-05"))
                .timeout(Duration.ofSeconds(120)).GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("text/csv;charset=UTF-8", response.headers().firstValue("Content-Type").orElse(""));
        String disposition = response.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.contains("orders_20260101_20260105.csv"), "文件名应含日期区间，实际=" + disposition);

        byte[] bytes = response.body();
        assertTrue(bytes.length > 0);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.strip().split("\\R");
        assertEquals(10, lines[1].split(",").length, "导出行必须为 10 列，与 A18 导入列顺序一致");
        assertTrue(lines[0].contains("订单号") && lines[0].contains("总金额"));
    }

    @Test
    @DisplayName("A18 受理校验（无文件/空文件/非 CSV/列数错误 -> 400）与只有表头的 FAILED 状态机")
    void importValidation() throws Exception {
        // 缺少 file part
        String boundary = "----dshTestBoundary";
        String missingFileBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"append\"\r\n\r\n1\r\n"
                + "--" + boundary + "--\r\n";
        JsonNode noFile = postMultipart(boundary, missingFileBody);
        assertEquals(400, noFile.path("code").asInt(), "缺少文件必须返回 400，实际=" + noFile.path("code").asInt());

        // 非 csv 扩展名
        JsonNode notCsv = postMultipart(boundary, filePart(boundary, "x.txt", "a,b,c\n", "1"));
        assertEquals(400, notCsv.path("code").asInt());

        // 列数 != 10
        JsonNode badColumns = postMultipart(boundary, filePart(boundary, "bad.csv", "a,b,c\n1,2,3\n", "1"));
        assertEquals(400, badColumns.path("code").asInt());

        // 空文件
        JsonNode empty = postMultipart(boundary, filePart(boundary, "empty.csv", "", "1"));
        assertEquals(400, empty.path("code").asInt());

        // append 非法
        JsonNode badAppend = postMultipart(boundary, filePart(boundary, "ok.csv", "a,b,c\n", "2"));
        assertEquals(400, badAppend.path("code").asInt());

        // 只有表头（0 行数据）：受理成功，但状态机必须以 FAILED 收尾（validRows = 0）
        JsonNode headerOnly = postMultipart(boundary, filePart(boundary, "header.csv",
                "orderId,productName,category,price,quantity,totalAmount,orderTime,userId,userType,payType\n", "1"));
        assertEquals(0, headerOnly.path("code").asInt(), "合法 CSV 应受理成功：" + headerOnly.path("message").asText());
        String taskId = headerOnly.path("data").path("taskId").asText();
        assertTrue(taskId.startsWith("imp_"), "taskId 形如 imp_yyyyMMdd_HHmmssSSS，实际=" + taskId);

        JsonNode task = awaitTerminal(taskId);
        assertEquals("FAILED", task.path("status").asText(), "0 行有效数据必须收敛为 FAILED");
        assertEquals(0, task.path("validRows").asInt());
        assertTrue(task.has("endTime") && !task.path("endTime").isNull(), "终态必须有 endTime");
        assertTrue(task.path("endTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "时间格式必须为 yyyy-MM-dd HH:mm:ss，实际=" + task.path("endTime").asText());
    }

    /** 轮询任务直到终态（前端每 2 秒轮询的等价逻辑） */
    private JsonNode awaitTerminal(String taskId) throws Exception {
        for (int i = 0; i < 40; i++) {
            JsonNode task = ok("/api/orders/import/tasks/" + taskId);
            String status = task.path("status").asText();
            assertTrue(status.matches("PROCESSING|SUCCESS|PARTIAL|FAILED"), "状态字面量必须合法，实际=" + status);
            if (!"PROCESSING".equals(status)) {
                return task;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("导入任务在 20 秒内未进入终态：" + taskId);
    }

    private String filePart(String boundary, String fileName, String content, String append) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + content + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"append\"\r\n\r\n" + append + "\r\n"
                + "--" + boundary + "--\r\n";
    }

    @Test
    @DisplayName("预聚合任务（P1）：可重复执行、先删后插")
    void dailySummaryRebuild() {
        LocalDate statDate = LocalDate.of(2026, 6, 1);
        int rows = dailySummaryJob.rebuild(statDate);
        assertTrue(rows > 0, "2026-06-01 有订单，预聚合应写入多行");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_summary WHERE stat_date = ?", Integer.class, statDate);
        assertEquals(rows, count, "写入行数与库中行数一致");
        // 重复执行结果一致（先删后插，可补跑）
        int again = dailySummaryJob.rebuild(statDate);
        assertEquals(rows, again, "重复执行必须幂等");
        Integer distinct = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT category, pay_type, user_type) FROM daily_summary WHERE stat_date = ?",
                Integer.class, statDate);
        assertEquals(rows, distinct, "四个维度组合不可重复");
    }

    private JsonNode postMultipart(String boundary, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/orders/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        assertEquals(200, response.statusCode(), "业务错误码必须走 body（HTTP 200）");
        return MAPPER.readTree(response.body());
    }

    @Test
    @DisplayName("响应体 null 字段不被剔除（前端依赖 prevSales:null 显示「无对比数据」）")
    void nullFieldsSurvive() throws Exception {
        String raw = send(HttpRequest.newBuilder(URI.create(base()
                        + "/api/orders/trend?granularity=month&compareType=previousYear&" + RANGE))
                .timeout(Duration.ofSeconds(120)).GET().build()).body();
        assertTrue(raw.contains("\"prevSales\":null"), "null 字段必须保留在 JSON 中，实际响应=" + raw.substring(0, Math.min(400, raw.length())));
        assertFalse(raw.contains("\"prevSales\":0,"), "缺失对比数据不能伪装成 0");
    }
}