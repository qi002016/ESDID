package com.heima.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.common.ErrorCode;
import com.heima.common.constant.ImportStatus;
import com.heima.domain.dto.ImportSubmitDTO;
import com.heima.domain.po.ImportTask;
import com.heima.domain.vo.ImportTaskVO;
import com.heima.exception.BizException;
import com.heima.mapper.ImportTaskErrorMapper;
import com.heima.mapper.ImportTaskMapper;
import com.heima.service.IOrderImportService;
import com.heima.task.ImportTaskExecutor;
import com.heima.util.CsvUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * A18/A19 CSV 批量导入服务实现。
 * <p>受理阶段做同步校验（空文件 / 扩展名 / 列数 / 重复导入 409），随后交给异步执行器。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderImportServiceImpl implements IOrderImportService {

    /** taskId 时间戳格式 */
    private static final DateTimeFormatter TASK_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");

    private final ImportTaskMapper importTaskMapper;

    private final ImportTaskErrorMapper importTaskErrorMapper;

    private final ImportTaskExecutor importTaskExecutor;

    @Value("${dashboard.import.max-error-detail:500}")
    private int maxErrorDetail;

    // ==================== A18 受理 ====================

    @Override
    public ImportSubmitDTO submit(MultipartFile file, int append) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "upload.csv";
        if (!fileName.toLowerCase().endsWith(".csv")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 .csv 文件");
        }

        byte[] bytes = file.getBytes();
        validateColumnCount(bytes);

        String fileHash = sha256(bytes);

        // 409：同一文件或全局已有处理中任务
        if (importTaskMapper.countProcessingByHash(fileHash) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "同一文件已有导入任务正在处理中，请稍后再试");
        }
        if (importTaskMapper.countProcessing() > 0) {
            throw new BizException(ErrorCode.CONFLICT, "已有导入任务正在处理中，请稍后再试");
        }

        ImportTask task = new ImportTask();
        task.setTaskId("imp_" + LocalDateTime.now().format(TASK_ID_FMT));
        task.setFileName(fileName);
        task.setFileHash(fileHash);
        task.setAppendMode(append);
        task.setStatus(ImportStatus.PROCESSING);
        task.setTotalRows(0);
        task.setValidRows(0);
        task.setErrorRows(0);
        task.setStartTime(LocalDateTime.now());
        importTaskMapper.insert(task);

        // 异步执行，立即返回 taskId
        importTaskExecutor.runAsync(task.getTaskId(), bytes, append);
        log.info("导入任务已受理 taskId={} file={} append={} size={}B",
                task.getTaskId(), fileName, append, bytes.length);
        return new ImportSubmitDTO(task.getTaskId(), ImportStatus.PROCESSING);
    }

    // ==================== A19 状态查询 ====================

    @Override
    public ImportTaskVO getTask(String taskId) {
        ImportTask task = importTaskMapper.selectOne(
                Wrappers.<ImportTask>lambdaQuery().eq(ImportTask::getTaskId, taskId));
        if (task == null) {
            return null;
        }
        List<ImportTaskVO.ImportErrorVO> errors = ImportStatus.PROCESSING.equals(task.getStatus())
                ? Collections.emptyList()
                : importTaskErrorMapper.listByTaskId(taskId, maxErrorDetail);

        return ImportTaskVO.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .fileName(task.getFileName())
                .totalRows(task.getTotalRows())
                .validRows(task.getValidRows())
                .errorRows(task.getErrorRows())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .failReason(task.getFailReason())
                .errors(errors)
                .build();
    }

    // ==================== 内部工具 ====================

    /** 同步校验列数（非表头首行的列数必须为 10） */
    private void validateColumnCount(byte[] bytes) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine && CsvUtils.isHeader(line)) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;
                if (line.isBlank()) {
                    continue;
                }
                List<String> cols = new ArrayList<>(CsvUtils.split(line));
                if (cols.size() != CsvUtils.FIELDS.length) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "列数必须为 10 列，当前 " + cols.size() + " 列");
                }
                return;
            }
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "SHA-256 算法不可用");
        }
    }
}