package com.hmdp.agent.observability.backend;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 观测后端配置绑定（前缀 {@code hmdp.ai-observability.backend}）。
 * <p>
 * 仅承载<b>行为决策配置</b>（选哪个后端、能力覆盖开关），<b>不承载接入参数</b>
 * ——endpoint/headers/鉴权唯一事实源是 {@code management.otlp.tracing.*} yaml /
 * 环境变量（观测后端解耦改造方案 §6.1，评审 13.2.1）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai-observability.backend")
public class BackendProperties {

    /**
     * 观测后端类型：{@link TraceBackendType} 合法值（langfuse/jaeger/signoz/collector/console/noop）。
     * 缺省 = langfuse（兼容现状，Fail-Open 分级见装配器）；未知值由装配器降级 Noop。
     */
    private String type = "langfuse";
}