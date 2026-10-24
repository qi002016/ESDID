package com.heima.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与定时任务配置（《后端开发文档》§6.2）。
 * <p>导入线程池：核心 2 / 最大 4 / 队列 50，队列满由调用线程执行，避免任务被静默丢弃。</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String IMPORT_EXECUTOR = "importExecutor";

    @Bean(IMPORT_EXECUTOR)
    public ThreadPoolTaskExecutor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("csv-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}