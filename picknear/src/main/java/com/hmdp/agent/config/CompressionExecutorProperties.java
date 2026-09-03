package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 异步压缩执行器配置。
 * <p>
 * 配置项前缀：agent.compression-executor
 * </p>
 * <p>
 * 压缩线程池与请求链隔离：主链（aiTaskExecutor 流式 / subtaskExecutor 规划）延迟敏感；
 * 压缩走小模型、秒级、失败可重试，混池会互相饿死。拒绝用 AbortPolicy（不用 CallerRuns，
 * 防止压缩占用 SSE/Tomcat 收尾线程）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.compression-executor")
public class CompressionExecutorProperties {

    /** 压缩池核心线程 */
    private int core = 1;

    /** 压缩池最大线程 */
    private int max = 2;

    /** 压缩任务队列容量 */
    private int queue = 512;

    /** 低频清扫兜底周期（dirty 自愈 / TTL / 归档清理） */
    private Duration sweeperInterval = Duration.ofMinutes(5);

    /** 会话压缩互斥锁 lease */
    private Duration lockLease = Duration.ofSeconds(60);

    /** 单次 worker 压缩循环最多追平次数（防活锁） */
    private int catchUpMax = 2;
}