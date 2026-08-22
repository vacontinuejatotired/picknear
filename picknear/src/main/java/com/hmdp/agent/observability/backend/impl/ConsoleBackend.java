package com.hmdp.agent.observability.backend.impl;

import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.backend.TraceBackendCapabilities;
import org.springframework.stereotype.Component;

/**
 * 本地控制台后端：本地调试用，无外部服务依赖（语义经日志/控制台查看，放大输出不关注配额）。
 */
@Component
public class ConsoleBackend implements TraceBackend {

    @Override
    public String id() {
        return "console";
    }

    @Override
    public TraceBackendCapabilities capabilities() {
        return TraceBackendCapabilities.console();
    }
}