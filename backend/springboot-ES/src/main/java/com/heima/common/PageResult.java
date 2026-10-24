package com.heima.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页查询结果（《后端开发文档》§5 A2）。
 * 序列化为 {@code {total, page, size, records}}。
 *
 * @param <T> 记录类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private Integer page;

    /** 每页大小 */
    private Integer size;

    /** 当前页数据 */
    private List<T> records;

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(0L, page, size, Collections.emptyList());
    }
}