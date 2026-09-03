package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.CompressionExecutorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 独立压缩线程池 + 调度启用。
 * <p>
 * 与 aiTaskExecutor（流式推送）/ subtaskExecutor（规划执行）隔离：压缩走小模型、秒级、
 * 失败可重试，混池会互相饿死；拒绝用 AbortPolicy（不用 CallerRuns，防压缩占用请求收尾线程），
 * 拒绝时由 Dispatcher 置 dirty 交 sweeper 自愈。
 * </p>
 */
@Configuration
@EnableScheduling
public class CompressionExecutorConfig {

    @Bean("compressExecutor")
    public Executor compressExecutor(CompressionExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCore());
        executor.setMaxPoolSize(properties.getMax());
        executor.setQueueCapacity(properties.getQueue());
        executor.setThreadNamePrefix("compress-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new CleanupTaskDecorator());
        executor.initialize();
        return executor;
    }
}