package com.heima.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.domain.po.ImportTaskError;
import com.heima.domain.vo.ImportTaskVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 导入错误明细 Mapper（A19 的 errors 数组）。
 */
@Mapper
public interface ImportTaskErrorMapper extends BaseMapper<ImportTaskError> {

    /** 批量写入错误明细 */
    int batchInsert(@Param("list") List<ImportTaskError> list);

    /** 按任务查询错误明细（最多 limit 条，按行号升序） */
    List<ImportTaskVO.ImportErrorVO> listByTaskId(@Param("taskId") String taskId,
                                                  @Param("limit") int limit);
}