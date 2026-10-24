package com.heima.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / Knife4j 分组（《后端开发文档》§8.3），便于答辩演示时按业务查看接口。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi orderApi() {
        return GroupedOpenApi.builder().group("01-订单与统计").pathsToMatch("/api/orders/**").build();
    }

    @Bean
    public GroupedOpenApi targetApi() {
        return GroupedOpenApi.builder().group("02-销售目标").pathsToMatch("/api/targets/**").build();
    }

    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder().group("03-用户分析").pathsToMatch("/api/users/**").build();
    }

    @Bean
    public GroupedOpenApi baseApi() {
        return GroupedOpenApi.builder().group("04-基础数据").pathsToMatch("/api/categories/**").build();
    }
}