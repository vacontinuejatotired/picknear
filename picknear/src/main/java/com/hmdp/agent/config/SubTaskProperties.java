package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 子 Agent 执行配置。
 * <p>
 * 配置项前缀：agent.subtask
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.subtask")
public class SubTaskProperties {

    /** 子 Agent 单次 LLM 调用超时 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 整个 execute() 总超时（含重试），超时直接终止 */
    private Duration totalTimeout = Duration.ofSeconds(60);

    /** 最大重试次数（含首次调用） */
    private int maxRetries = 3;

    /** 重试基础退避间隔（指数增长：1s → 2s → 4s） */
    private Duration retryBackoff = Duration.ofSeconds(1);
}
