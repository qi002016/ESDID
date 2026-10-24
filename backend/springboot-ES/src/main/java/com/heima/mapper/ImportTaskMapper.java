package com.heima.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.domain.po.ImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 导入任务 Mapper（A18/A19）。
 */
@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {

    /** 是否已有处理中的任务（全局） */
    int countProcessing();

    /** 同一文件（sha256）是否已有处理中的任务 */
    int countProcessingByHash(@Param("fileHash") String fileHash);

    /** 写入终态与统计结果 */
    int finish(@Param("taskId") String taskId,
               @Param("status") String status,
               @Param("totalRows") int totalRows,
               @Param("validRows") int validRows,
               @Param("errorRows") int errorRows,
               @Param("endTime") LocalDateTime endTime,
               @Param("failReason") String failReason);
}