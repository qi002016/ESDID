package com.heima.controller;

import com.heima.common.ErrorCode;
import com.heima.common.PageResult;
import com.heima.common.Result;
import com.heima.domain.dto.ImportSubmitDTO;
import com.heima.domain.dto.OrderQueryDTO;
import com.heima.domain.vo.ImportTaskVO;
import com.heima.domain.vo.OrderVO;
import com.heima.exception.BizException;
import com.heima.service.IOrderImportService;
import com.heima.service.IOrdersService;
import com.heima.util.DateRangeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;

/**
 * 订单基础接口：A2 分页查询、A6 详情、A12 导出、A18/A19 导入。
 * <p>注意路由：{@code /api/orders/export}、{@code /api/orders/import/**} 为精确路径，
 * Spring 的精确匹配优先于 {@code /api/orders/{orderId}}，无需特殊处理。</p>
 */
@Tag(name = "订单与统计", description = "A2 分页 / A6 详情 / A12 导出 / A18 导入 / A19 任务状态")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrdersController {

    private final IOrdersService ordersService;

    private final IOrderImportService orderImportService;

    // ==================== A2 订单分页查询 ====================

    @Operation(summary = "A2 订单分页查询（sort 白名单：orderTime/totalAmount/price/quantity）")
    @GetMapping
    public Result<PageResult<OrderVO>> page(@Valid OrderQueryDTO query) {
        return Result.ok(ordersService.pageOrders(query));
    }

    // ==================== A6 订单详情 ====================

    @Operation(summary = "A6 订单详情（订单号不存在返回 404）")
    @GetMapping("/{orderId}")
    public Result<OrderVO> detail(@PathVariable String orderId) {
        OrderVO vo = ordersService.getByOrderId(orderId);
        if (vo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在：" + orderId);
        }
        return Result.ok(vo);
    }

    // ==================== A12 CSV 流式导出 ====================

    @Operation(summary = "A12 订单明细 CSV 导出（流式，带 Content-Disposition）")
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@Valid OrderQueryDTO query) {
        // 先做日期缺省化，保证响应文件名与实际导出区间一致
        String[] range = DateRangeUtils.normalize(query.getStartDate(), query.getEndDate());
        query.setStartDate(range[0]);
        query.setEndDate(range[1]);

        String filename = "orders_%s_%s.csv".formatted(
                range[0].replace("-", ""), range[1].replace("-", ""));

        StreamingResponseBody body = outputStream -> ordersService.exportCsv(query, outputStream);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    // ==================== A18 CSV 批量导入 ====================

    @Operation(summary = "A18 CSV 批量导入（异步，返回 taskId；重复导入返回 409）")
    @PostMapping("/import")
    public Result<ImportSubmitDTO> importCsv(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "append", defaultValue = "0") Integer append)
            throws IOException {
        if (append == null || (append != 0 && append != 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "append 仅支持 0（覆盖导入）/ 1（追加导入）");
        }
        return Result.ok(orderImportService.submit(file, append));
    }

    // ==================== A19 导入任务状态 ====================

    @Operation(summary = "A19 导入任务状态（taskId 不存在返回 404）")
    @GetMapping("/import/tasks/{taskId}")
    public Result<ImportTaskVO> importTask(@PathVariable String taskId) {
        ImportTaskVO task = orderImportService.getTask(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导入任务不存在：" + taskId);
        }
        return Result.ok(task);
    }
}