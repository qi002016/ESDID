package com.heima.service;

import com.heima.domain.dto.ImportSubmitDTO;
import com.heima.domain.vo.ImportTaskVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * CSV 批量导入服务（A18/A19）。
 */
public interface IOrderImportService {

    /** A18 受理导入（同步校验 + 异步执行），返回 taskId */
    ImportSubmitDTO submit(MultipartFile file, int append) throws IOException;

    /** A19 查询任务状态，不存在返回 null（由 Controller 转 404） */
    ImportTaskVO getTask(String taskId);
}