package com.heima.service.impl;

import com.heima.domain.dto.ImportResult;
import com.heima.domain.po.ImportTaskError;
import com.heima.domain.po.Orders;
import com.heima.mapper.OrdersMapper;
import com.heima.util.CsvUtils;
import com.heima.util.OrderCsvParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A18 导入的<b>数据写入</b>部分：流式读取 + 逐行校验 + 批量写入。
 * <p>整个方法在一个事务内执行：覆盖导入的「清空」与「写入」同事务提交，
 * 出现文件级/数据库级异常时整体回滚，不会出现「清空成功、写入失败」丢数据的中间态；
 * 且清空动作只在出现第一行有效数据时才执行，全文件无效（FAILED）时保留原有数据。</p>
 * <p>错误行不中断导入，逐行记录（最多保留 max-error-detail 条明细）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportDataTxService {

    private final OrdersMapper ordersMapper;

    @Value("${dashboard.import.batch-size:500}")
    private int batchSize;

    @Value("${dashboard.import.max-error-detail:500}")
    private int maxErrorDetail;

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importCsv(String taskId, byte[] bytes, int append) throws IOException {
        int totalRows = 0;
        int validRows = 0;
        int errorRows = 0;
        List<ImportTaskError> errors = new ArrayList<>();
        List<Orders> buffer = new ArrayList<>(batchSize);
        // 覆盖导入的清空动作延迟到「确实有有效行」时执行：
        // 若整个文件没有一行有效数据（validRows=0，状态机收敛为 FAILED），则不动原有数据，避免“清空成功、写入全失败”后看板无数据
        // 标记语义：cleared=false 表示「尚未清空」，遇到第一个有效行时清空一次；
        // 覆盖导入(append=0)初始为 false（需清空），追加导入(append=1)初始为 true（永不清空）
        boolean cleared = append != 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {

            String line;
            int lineNo = 0;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                // 兼容有/无表头
                if (firstLine && CsvUtils.isHeader(line)) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;
                if (line.isBlank()) {
                    continue;
                }
                totalRows++;

                List<String> cols = CsvUtils.split(line);
                String error = OrderCsvParser.validate(cols);
                if (error != null) {
                    errorRows++;
                    if (errors.size() < maxErrorDetail) {
                        errors.add(new ImportTaskError(taskId, lineNo,
                                OrderCsvParser.fieldOf(error), OrderCsvParser.messageOf(error)));
                    }
                    continue;
                }

                if (!cleared) {
                    ordersMapper.deleteAllOrders();
                    cleared = true;
                }
                buffer.add(OrderCsvParser.toEntity(cols));
                validRows++;
                // 每 batchSize 行批量写入，避免逐条往返
                if (buffer.size() >= batchSize) {
                    ordersMapper.batchInsert(buffer);
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                ordersMapper.batchInsert(buffer);
            }
        }

        return ImportResult.builder()
                .totalRows(totalRows)
                .validRows(validRows)
                .errorRows(errorRows)
                .errors(errors)
                .build();
    }
}