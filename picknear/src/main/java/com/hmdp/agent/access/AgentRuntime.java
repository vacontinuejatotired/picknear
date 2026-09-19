package com.hmdp.agent.access;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 运行时边界。
 *
 * <p>接入层只依赖该接口。当前实现是旧链路的适配，后续可以替换为 Alibaba Graph
 * 实现，而不需要修改 Controller。</p>
 */
public interface AgentRuntime {

    /**
     * 执行一次 Agent 对话请求。
     *
     * @return 已装配完成的 SSE 连接
     */
    SseEmitter run(AgentCommand command);
}
