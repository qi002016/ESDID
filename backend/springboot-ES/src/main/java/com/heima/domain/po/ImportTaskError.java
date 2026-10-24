package com.heima.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 导入错误行明细（A19 的 {@code errors} 数组，表 {@code import_task_error}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("import_task_error")
@Schema(description = "导入错误行明细")
public class ImportTaskError implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String taskId;

    @Schema(description = "CSV 行号（含表头，从 1 开始）")
    private Integer rowNo;

    @Schema(description = "出错字段")
    private String field;

    @Schema(description = "错误原因")
    private String message;

    public ImportTaskError(String taskId, Integer rowNo, String field, String message) {
        this.taskId = taskId;
        this.rowNo = rowNo;
        this.field = field;
        this.message = message;
    }
}