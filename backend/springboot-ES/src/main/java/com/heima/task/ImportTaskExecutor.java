package com.heima.task;

import com.heima.common.constant.ImportStatus;
import com.heima.config.AsyncConfig;
import com.heima.domain.dto.ImportResult;
import com.heima.domain.po.ImportTaskError;
import com.heima.mapper.ImportTaskErrorMapper;
import com.heima.mapper.ImportTaskMapper;
import com.heima.service.ICategoryService;
import com.heima.service.ISalesTargetService;
import com.heima.service.impl.ImportDataTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A18 导入任务执行器：负责状态机推进与结果落库。
 * <pre>
 * PROCESSING ─┬─► SUCCESS（errorRows = 0）
 *             ├─► PARTIAL（validRows &gt; 0 且 errorRows &gt; 0）
 *             └─► FAILED（validRows = 0 或文件级异常）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskExecutor {

    private static final int FAIL_REASON_MAX_LENGTH = 500;

    private final ImportDataTxService importDataTxService;

    private final ImportTaskMapper importTaskMapper;

    private final ImportTaskErrorMapper importTaskErrorMapper;

    private final ICategoryService categoryService;

    private final ISalesTargetService salesTargetService;

    /**
     * 异步执行导入。
     * <p>数据写入在 {@link ImportDataTxService} 的事务中完成；任务状态与错误明细在事务提交后写入，
     * 保证即使数据回滚也能看到 FAILED 与失败原因。</p>
     */
    @Async(AsyncConfig.IMPORT_EXECUTOR)
    public void runAsync(String taskId, byte[] bytes, int append) {
        ImportResult result;
        try {
            result = importDataTxService.importCsv(taskId, bytes, append);
        } catch (Exception e) {
            log.error("导入任务失败 taskId={}", taskId, e);
            importTaskMapper.finish(taskId, ImportStatus.FAILED, 0, 0, 0,
                    LocalDateTime.now(), truncate(e.getMessage()));
            return;
        }

        String status = resolveStatus(result);
        importTaskMapper.finish(taskId, status, result.getTotalRows(), result.getValidRows(),
                result.getErrorRows(), LocalDateTime.now(), null);
        saveErrors(result.getErrors());

        // 导入后主动失效缓存，保证看板立即反映新数据
        if (result.getValidRows() > 0) {
            categoryService.evictCategoryCache();
            salesTargetService.evictTargetCache();
        }
        log.info("导入完成 taskId={} total={} valid={} error={} status={}",
                taskId, result.getTotalRows(), result.getValidRows(), result.getErrorRows(), status);
    }

    private String resolveStatus(ImportResult result) {
        // 文档 §6.1：SUCCESS（errorRows=0）/ PARTIAL（validRows>0 且 errorRows>0）/ FAILED（validRows=0）
        if (result.getValidRows() <= 0) {
            return ImportStatus.FAILED;
        }
        return result.getErrorRows() > 0 ? ImportStatus.PARTIAL : ImportStatus.SUCCESS;
    }

    /** 错误明细批量写入（表里全量留存，A19 返回时最多 500 条） */
    private void saveErrors(List<ImportTaskError> errors) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        try {
            importTaskErrorMapper.batchInsert(errors);
        } catch (Exception e) {
            // 错误明细写入失败不影响任务终态
            log.error("导入错误明细写入失败 size={}", errors.size(), e);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= FAIL_REASON_MAX_LENGTH
                ? message : message.substring(0, FAIL_REASON_MAX_LENGTH);
    }
}