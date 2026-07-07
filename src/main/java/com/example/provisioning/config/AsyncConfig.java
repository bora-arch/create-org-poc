package com.example.provisioning.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async infrastructure for the provisioning workflow.
 *
 * <p>The workflow runs on a dedicated pool rather than the default
 * {@code SimpleAsyncTaskExecutor} so provisioning load never contends
 * with (or starves) other application threads, and so operators can
 * observe threads by name in dumps and metrics.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String PROVISIONING_EXECUTOR = "provisioningExecutor";

    @Bean(name = PROVISIONING_EXECUTOR)
    public Executor provisioningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("provision-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
