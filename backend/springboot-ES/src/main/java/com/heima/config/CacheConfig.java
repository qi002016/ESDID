package com.heima.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.heima.domain.vo.CategoryVO;
import com.heima.domain.vo.TargetListVO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 本地缓存配置（《后端开发文档》§9.2 P1）。
 * <p>品类列表 TTL 10 分钟（A1）、销售目标 TTL 5 分钟（A13）；导入任务完成后主动失效。
 * 直接使用 Caffeine 实例而非 Spring Cache 抽象，语义更显式、便于单测。</p>
 */
@Configuration
public class CacheConfig {

    /** A1 品类列表缓存 */
    @Bean("categoryListCache")
    public Cache<String, List<CategoryVO>> categoryListCache() {
        return Caffeine.newBuilder()
                .maximumSize(16)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    /** A13 销售目标缓存 */
    @Bean("targetListCache")
    public Cache<String, TargetListVO> targetListCache() {
        return Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }
}