package com.heima.service;

import com.heima.common.ErrorCode;
import com.heima.common.constant.ImportStatus;
import com.heima.domain.dto.ImportSubmitDTO;
import com.heima.domain.po.ImportTask;
import com.heima.domain.vo.ImportTaskVO;
import com.heima.exception.BizException;
import com.heima.mapper.ImportTaskErrorMapper;
import com.heima.mapper.ImportTaskMapper;
import com.heima.service.impl.OrderImportServiceImpl;
import com.heima.task.ImportTaskExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OrderImportServiceImpl} 单元测试（A18 受理校验 / A19 任务查询）。
 * <p>覆盖《后端开发文档》§10.1 的「A19 不存在 taskId」以及 §6.3 受理阶段的 400/409 分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderImportServiceImplTest {

    private static final String HEADER = "订单号,商品名,品类,单价,数量,总金额,下单时间,用户ID,用户类型,支付方式";
    private static final String VALID_ROW = "ORD001,手机,数码,100.00,2,200.00,2026-09-01 10:00:00,U001,new,alipay";
    private static final String VALID_CSV = HEADER + "\n" + VALID_ROW + "\n";

    @Mock
    private ImportTaskMapper importTaskMapper;

    @Mock
    private ImportTaskErrorMapper importTaskErrorMapper;

    @Mock
    private ImportTaskExecutor importTaskExecutor;

    @InjectMocks
    private OrderImportServiceImpl service;

    private static MultipartFile csv(String fileName, String content) {
        return new MockMultipartFile("file", fileName, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== A19 任务状态查询 ====================

    @Test
    @DisplayName("A19 不存在的 taskId：Service 返回 null（由 Controller 转 404）")
    void getTask_unknownTaskId_returnsNull() {
        when(importTaskMapper.selectOne(any())).thenReturn(null);

        ImportTaskVO vo = service.getTask("imp_not_exist");

        assertThat(vo).isNull();
        // 任务不存在时不应再去查错误明细
        verifyNoInteractions(importTaskErrorMapper);
    }

    @Test
    @DisplayName("A19 PROCESSING 中的任务不返回错误明细（errors=[]）")
    void getTask_processingTask_returnsEmptyErrors() {
        ImportTask task = new ImportTask();
        task.setTaskId("imp_20260916_001");
        task.setStatus(ImportStatus.PROCESSING);
        task.setFileName("orders.csv");
        task.setTotalRows(0);
        task.setValidRows(0);
        task.setErrorRows(0);
        task.setStartTime(LocalDateTime.of(2026, 9, 16, 10, 0, 0));
        when(importTaskMapper.selectOne(any())).thenReturn(task);

        ImportTaskVO vo = service.getTask("imp_20260916_001");

        assertThat(vo).isNotNull();
        assertThat(vo.getTaskId()).isEqualTo("imp_20260916_001");
        assertThat(vo.getStatus()).isEqualTo(ImportStatus.PROCESSING);
        assertThat(vo.getErrors()).isEmpty();
        verifyNoInteractions(importTaskErrorMapper);
    }

    @Test
    @DisplayName("A19 终态任务返回错误明细（最多 maxErrorDetail 条）")
    void getTask_finishedTask_returnsErrorDetails() {
        ImportTask task = new ImportTask();
        task.setTaskId("imp_20260916_002");
        task.setStatus(ImportStatus.PARTIAL);
        task.setTotalRows(5200);
        task.setValidRows(5180);
        task.setErrorRows(20);
        when(importTaskMapper.selectOne(any())).thenReturn(task);

        ImportTaskVO.ImportErrorVO error = ImportTaskVO.ImportErrorVO.builder()
                .row(12).field("totalAmount").message("总金额与单价×数量不一致").build();
        when(importTaskErrorMapper.listByTaskId(eq("imp_20260916_002"), anyInt()))
                .thenReturn(List.of(error));

        ImportTaskVO vo = service.getTask("imp_20260916_002");

        assertThat(vo.getStatus()).isEqualTo(ImportStatus.PARTIAL);
        assertThat(vo.getErrorRows()).isEqualTo(20);
        assertThat(vo.getErrors()).hasSize(1);
        assertThat(vo.getErrors().get(0).getField()).isEqualTo("totalAmount");
    }

    // ==================== A18 受理校验 ====================

    @Test
    @DisplayName("A18 空文件 -> BizException(400)「文件为空」")
    void submit_emptyFile_throws400() {
        assertThatThrownBy(() -> service.submit(csv("orders.csv", ""), 0))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getErrorCode().getCode()).isEqualTo(400);
                });
        // file == null 也要 400
        assertThatThrownBy(() -> service.submit(null, 0)).isInstanceOf(BizException.class);
        verifyNoInteractions(importTaskMapper, importTaskExecutor);
    }

    @Test
    @DisplayName("A18 非 .csv 扩展名 -> BizException(400)")
    void submit_nonCsvFile_throws400() {
        assertThatThrownBy(() -> service.submit(csv("orders.txt", VALID_CSV), 0))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains(".csv");
                });
        verifyNoInteractions(importTaskMapper, importTaskExecutor);
    }

    @Test
    @DisplayName("A18 列数 != 10 -> BizException(400)，且不落任务")
    void submit_wrongColumnCount_throws400() {
        assertThatThrownBy(() -> service.submit(csv("orders.csv", "ORD001,手机,数码\n"), 0))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("10");
                });
        // 校验放在 sha256 / 查重之前，不应触发任何 Mapper 交互
        verifyNoInteractions(importTaskMapper, importTaskExecutor);
    }

    @Test
    @DisplayName("A18 同一 file_hash 已有 PROCESSING 任务 -> BizException(409)")
    void submit_sameHashProcessingTask_throws409() {
        when(importTaskMapper.countProcessingByHash(anyString())).thenReturn(1);

        assertThatThrownBy(() -> service.submit(csv("orders.csv", VALID_CSV), 0))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo(409);
                });
        verify(importTaskMapper, never()).insert(any(ImportTask.class));
        verifyNoInteractions(importTaskExecutor);
    }

    @Test
    @DisplayName("A18 全局已有 PROCESSING 任务 -> BizException(409)")
    void submit_globalProcessingTask_throws409() {
        when(importTaskMapper.countProcessingByHash(anyString())).thenReturn(0);
        when(importTaskMapper.countProcessing()).thenReturn(1);

        assertThatThrownBy(() -> service.submit(csv("orders.csv", VALID_CSV), 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo(409);
                });
        verify(importTaskMapper, never()).insert(any(ImportTask.class));
        verifyNoInteractions(importTaskExecutor);
    }

    @Test
    @DisplayName("A18 正常受理：入库 PROCESSING 任务并同步派发异步执行器，返回 taskId")
    void submit_validCsv_insertsTaskAndDispatchesAsync() throws Exception {
        when(importTaskMapper.countProcessingByHash(anyString())).thenReturn(0);
        when(importTaskMapper.countProcessing()).thenReturn(0);

        ImportSubmitDTO result = service.submit(csv("orders.csv", VALID_CSV), 1);

        assertThat(result.getStatus()).isEqualTo(ImportStatus.PROCESSING);
        assertThat(result.getTaskId()).startsWith("imp_");

        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(importTaskMapper).insert(taskCaptor.capture());
        ImportTask task = taskCaptor.getValue();
        assertThat(task.getTaskId()).isEqualTo(result.getTaskId());
        assertThat(task.getFileName()).isEqualTo("orders.csv");
        assertThat(task.getStatus()).isEqualTo(ImportStatus.PROCESSING);
        assertThat(task.getAppendMode()).isEqualTo(1);
        assertThat(task.getTotalRows()).isZero();
        assertThat(task.getStartTime()).isNotNull();
        // file_hash 为 64 位十六进制的 SHA-256
        assertThat(task.getFileHash()).hasSize(64).matches("[0-9a-f]{64}");

        verify(importTaskExecutor).runAsync(eq(result.getTaskId()), any(byte[].class), eq(1));
    }
}