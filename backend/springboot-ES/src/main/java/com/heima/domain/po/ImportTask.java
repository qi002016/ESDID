package com.heima.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * CSV 导入任务（A18/A19，表 {@code import_task}）。
 */
@Data
@TableName("import_task")
@Schema(description = "CSV 导入任务")
public class ImportTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务 ID，如 imp_20260916_001")
    private String taskId;

    @Schema(description = "上传文件名")
    private String fileName;

    @Schema(description = "文件 SHA-256，用于重复导入检测(409)")
    private String fileHash;

    @Schema(description = "0 覆盖 / 1 追加")
    private Integer appendMode;

    @Schema(description = "PROCESSING/SUCCESS/PARTIAL/FAILED")
    private String status;

    @Schema(description = "数据总行数（不含表头）")
    private Integer totalRows;

    @Schema(description = "校验通过行数")
    private Integer validRows;

    @Schema(description = "校验失败行数")
    private Integer errorRows;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Schema(description = "失败原因")
    private String failReason;
}