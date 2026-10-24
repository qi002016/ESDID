package com.heima.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求耗时日志（《后端开发文档》§8.5）：结构化输出 uri / method / status / cost。
 */
@Slf4j
@Component
@Order(1)
public class AccessLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            log.info("uri={} method={} status={} cost={}ms",
                    req.getRequestURI(), req.getMethod(), res.getStatus(), System.currentTimeMillis() - start);
        }
    }
}