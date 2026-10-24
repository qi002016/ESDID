package com.heima.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A19 导入任务状态（A18 受理后返回 {@code {taskId, status}}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "导入任务状态")
public class ImportTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;

    /** PROCESSING / SUCCESS / PARTIAL / FAILED */
    private String status;

    private String fileName;

    private Integer totalRows;

    private Integer validRows;

    private Integer errorRows;

    /** 前端按 yyyy-MM-dd HH:mm:ss 解析，不能是 ISO-8601 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime startTime;

    /** 终态时必有值 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime endTime;

    private String failReason;

    /** 错误明细（最多 500 条） */
    private List<ImportErrorVO> errors;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "导入错误明细")
    public static class ImportErrorVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** CSV 行号（含表头，从 1 开始） */
        private Integer row;

        private String field;

        private String message;
    }
}