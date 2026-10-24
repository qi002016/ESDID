package com.heima.domain.dto;

import com.heima.domain.po.ImportTaskError;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A18 导入执行结果（在事务内产生，事务提交后再落任务状态与错误明细）。
 */
@Data
@Builder
public class ImportResult {

    private int totalRows;

    private int validRows;

    private int errorRows;

    /** 最多保留 max-error-detail 条明细 */
    private List<ImportTaskError> errors;
}