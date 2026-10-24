package com.heima.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.heima.domain.vo.CategoryVO;
import com.heima.mapper.OrdersMapper;
import com.heima.service.ICategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * A1 品类列表服务实现。
 * <p>使用 Caffeine 本地缓存（TTL 10 分钟），避免品类下拉反复触发聚合查询；
 * 数据导入完成后由 {@link #evictCategoryCache()} 主动失效。</p>
 */
@Slf4j
@Service
public class CategoryServiceImpl implements ICategoryService {

    /** 缓存键：品类列表与查询条件无关 */
    private static final String CACHE_KEY = "all";

    private final OrdersMapper ordersMapper;

    private final Cache<String, List<CategoryVO>> categoryListCache;

    public CategoryServiceImpl(OrdersMapper ordersMapper,
                               @Qualifier("categoryListCache") Cache<String, List<CategoryVO>> categoryListCache) {
        this.ordersMapper = ordersMapper;
        this.categoryListCache = categoryListCache;
    }

    @Override
    public List<CategoryVO> listCategories() {
        List<CategoryVO> cached = categoryListCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        List<CategoryVO> categories = ordersMapper.countProductsByCategory();
        categoryListCache.put(CACHE_KEY, categories);
        return categories;
    }

    @Override
    public void evictCategoryCache() {
        categoryListCache.invalidateAll();
        log.info("品类缓存已失效（数据导入完成）");
    }
}