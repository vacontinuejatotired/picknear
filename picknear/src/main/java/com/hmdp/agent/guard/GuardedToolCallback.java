package com.hmdp.agent.guard;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class GuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolGuardManager guardManager;
    private final String conversationId;
    private final Long userId;
    private final boolean returnDirect;
    private final AgentTracer agentTracer;

    private static final AtomicInteger invokeCounter = new AtomicInteger(0);

    public GuardedToolCallback(ToolCallback delegate, ToolGuardManager guardManager,
                               String conversationId, Long userId, boolean returnDirect,
                               AgentTracer agentTracer) {
        this.delegate = delegate;
        this.guardManager = guardManager;
        this.conversationId = conversationId;
        this.userId = userId;
        this.returnDirect = returnDirect;
        this.agentTracer = agentTracer;
    }

    public String getToolName() {
        return delegate.getToolDefinition().name();
    }

    public String getToolDescription() {
        return delegate.getToolDefinition().description();
    }

    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String functionPayload) {
        return call(functionPayload, new ToolContext(Map.of()));
    }

    @Override
    public String call(String functionPayload, ToolContext toolContext) {
        if (toolContext == null) {
            toolContext = new ToolContext(Map.of());
        }
        String toolName = delegate.getToolDefinition().name();
        Long effectiveUserId = userId;

        if (toolContext != null && toolContext.getContext() != null) {
            Object uid = toolContext.getContext().get("userId");
            if (uid instanceof Long) {
                effectiveUserId = (Long) uid;
            }
        }

        ToolInvocationContext context = ToolInvocationContext.builder()
                .toolName(toolName)
                .arguments(functionPayload)
                .conversationId(conversationId)
                .userId(effectiveUserId)
                .invocationCount(invokeCounter.incrementAndGet())
                .build();

        GuardResult result = guardManager.evaluate(context);

        // 观测：agent.guard.{decision}.{toolName}（M1.5 规则：决策进 span 名，
        // Langfuse 不展示自定义属性；逐策略投票平铺为 M4 增强）
        try (AgentSpan guardSpan = agentTracer.start(AgentSpanSpec.GUARD,
                result.getDecision() + "." + toolName)) {
            guardSpan.attribute("tool.name", toolName);
            guardSpan.attribute("guard.policy", result.getPolicyName() != null
                    ? result.getPolicyName() : "none");

            switch (result.getDecision()) {
                case BLOCK -> {
                    String msg = result.getReason() != null
                            ? result.getReason()
                            : "操作已被安全策略拦截";
                    log.warn("工具调用被拦截 [tool={}, policy={}]", toolName, result.getPolicyName());
                    return returnDirect ? msg : "{\"error\":\"" + msg + "\"}";
                }
                case CONFIRM -> {
                    String msg = "该操作需要你的确认才能执行";
                    log.info("工具调用需确认 [tool={}, policy={}]", toolName, result.getPolicyName());
                    return returnDirect ? msg : "{\"confirm\":\"" + msg + "\"}";
                }
                case ALLOW -> {
                    log.debug("工具调用放行 [tool={}]", toolName);
                    return delegate.call(functionPayload, toolContext);
                }
            }
        }
        return delegate.call(functionPayload, toolContext);
    }
}
