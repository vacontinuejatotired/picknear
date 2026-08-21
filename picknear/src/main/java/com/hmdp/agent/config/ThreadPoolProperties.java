package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 线程池配置属性
 *
 * <p>供 DAG 规划执行器等模块读取外部化线程池参数。
 * 实际 Bean 由 {@link AgentConfig} 统一管理（含上下文传播）。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.subtask.thread-pool")
public class ThreadPoolProperties {
    private int corePoolSize = 4;
    private int maxPoolSize = 8;
    private int queueCapacity = 100;
    private int keepAliveSeconds = 60;
    private String threadNamePrefix = "dag-executor-";
}
