package com.heima.common.constant;

/**
 * A19 导入任务状态常量（前端按这 4 个字面量判断终态）。
 */
public final class ImportStatus {

    /** 处理中 */
    public static final String PROCESSING = "PROCESSING";

    /** 全部成功（errorRows = 0） */
    public static final String SUCCESS = "SUCCESS";

    /** 部分成功（validRows &gt; 0 且 errorRows &gt; 0） */
    public static final String PARTIAL = "PARTIAL";

    /** 失败（validRows = 0 或文件级异常） */
    public static final String FAILED = "FAILED";

    private ImportStatus() {
    }
}