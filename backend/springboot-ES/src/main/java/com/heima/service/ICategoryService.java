package com.heima.service;

import com.heima.domain.vo.CategoryVO;

import java.util.List;

/**
 * A1 品类列表服务。
 */
public interface ICategoryService {

    /** 品类列表（Caffeine 缓存，TTL 10 分钟） */
    List<CategoryVO> listCategories();

    /** 导入任务完成后主动失效品类缓存 */
    void evictCategoryCache();
}