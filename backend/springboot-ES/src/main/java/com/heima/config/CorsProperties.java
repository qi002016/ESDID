package com.heima.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域配置项（{@code dashboard.cors.allowed-origins}）。
 * <p>YAML 列表必须用 {@code @ConfigurationProperties} 绑定，{@code @Value} 无法解析列表占位符。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "dashboard.cors")
public class CorsProperties {

    /** 允许的前端来源，如 http://localhost:5173 */
    private List<String> allowedOrigins = new ArrayList<>();
}