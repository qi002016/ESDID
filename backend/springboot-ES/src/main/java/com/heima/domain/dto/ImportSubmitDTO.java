package com.heima.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A18 受理结果：{@code {taskId, status}}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportSubmitDTO {

    private String taskId;

    /** 固定为 PROCESSING */
    private String status;
}