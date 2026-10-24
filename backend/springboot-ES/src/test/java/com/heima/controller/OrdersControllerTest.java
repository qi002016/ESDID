package com.heima.controller;

import com.heima.common.ErrorCode;
import com.heima.common.Result;
import com.heima.domain.vo.ImportTaskVO;
import com.heima.domain.vo.OrderVO;
import com.heima.exception.BizException;
import com.heima.exception.GlobalExceptionHandler;
import com.heima.service.IOrderImportService;
import com.heima.service.IOrdersService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

/**
 * {@link OrdersController} 单元测试：A19/A6 的「资源不存在 -> 404」路径。
 * <p>覆盖《后端开发文档》§10.1「A19 不存在 taskId」：Service 返回 null 时 Controller 抛 {@code BizException(404)}，
 * 且经 {@link GlobalExceptionHandler} 落成 {@code {code:404,...}} 的响应体。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrdersControllerTest {

    @Mock
    private IOrdersService ordersService;

    @Mock
    private IOrderImportService orderImportService;

    @InjectMocks
    private OrdersController controller;

    // ==================== A19 ====================

    @Test
    @DisplayName("A19 taskId 不存在：Controller 抛 BizException(NOT_FOUND/404) 且 message 带上 taskId")
    void importTask_unknownTaskId_throws404() {
        when(orderImportService.getTask("imp_not_exist")).thenReturn(null);

        BizException ex = catchThrowableOfType(
                () -> controller.importTask("imp_not_exist"), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(404);
        assertThat(ex.getMessage()).contains("imp_not_exist");

        // 全局异常处理器把 BizException 转成统一响应体：code=404（前端按此提示）
        Result<Void> body = new GlobalExceptionHandler().handleBiz(ex);
        assertThat(body.getCode()).isEqualTo(404);
        assertThat(body.getMessage()).contains("imp_not_exist");
    }

    @Test
    @DisplayName("A19 路由必须是 GET /api/orders/import/tasks/{taskId}（精确路径优先于 /{orderId}）")
    void importTask_routeIsImportTasksPath() throws Exception {
        RequestMapping classMapping = OrdersController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping.value()).containsExactly("/api/orders");

        GetMapping methodMapping = OrdersController.class
                .getMethod("importTask", String.class)
                .getAnnotation(GetMapping.class);
        assertThat(methodMapping).isNotNull();
        assertThat(methodMapping.value()).containsExactly("/import/tasks/{taskId}");
    }

    @Test
    @DisplayName("A19 taskId 存在：返回 Result(code=0) 且 data 为任务状态")
    void importTask_existingTaskId_returnsOk() {
        ImportTaskVO task = ImportTaskVO.builder()
                .taskId("imp_20260916_001")
                .status("SUCCESS")
                .totalRows(10)
                .validRows(10)
                .errorRows(0)
                .build();
        when(orderImportService.getTask("imp_20260916_001")).thenReturn(task);

        Result<ImportTaskVO> result = controller.importTask("imp_20260916_001");

        assertThat(result.getCode()).isZero();
        assertThat(result.getMessage()).isEqualTo("success");
        assertThat(result.getData().getTaskId()).isEqualTo("imp_20260916_001");
    }

    // ==================== A6（附带：同一 404 约定） ====================

    @Test
    @DisplayName("A6 订单号不存在：Controller 抛 BizException(NOT_FOUND/404)")
    void detail_unknownOrderId_throws404() throws Exception {
        when(ordersService.getByOrderId("NOT_EXIST")).thenReturn(null);

        BizException ex = catchThrowableOfType(
                () -> controller.detail("NOT_EXIST"), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode().getCode()).isEqualTo(404);
        assertThat(new GlobalExceptionHandler().handleBiz(ex).getCode()).isEqualTo(404);

        RequestMapping classMapping = OrdersController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping.value()).containsExactly("/api/orders");
        assertThat(OrdersController.class.getMethod("detail", String.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/{orderId}");
    }

    @Test
    @DisplayName("A6 订单号存在：返回 Result(code=0)")
    void detail_existingOrderId_returnsOk() {
        OrderVO vo = new OrderVO();
        vo.setOrderId("ORD001");
        when(ordersService.getByOrderId("ORD001")).thenReturn(vo);

        Result<OrderVO> result = controller.detail("ORD001");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getOrderId()).isEqualTo("ORD001");
    }
}