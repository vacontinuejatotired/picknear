package com.hmdp.agent.guard;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.tool.ToolCallExecutor;
import com.hmdp.agent.tool.ToolDefinitionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 守卫包装工具回调（薄壳门面）。
 * <p>
 * 职责拆分（3.6 建议）：本类只保留回调协议（ToolCallback 接口 + 元数据代理 + 上下文装配），
 * 决策小步委托 {@link ToolGuardGate}（策略投票 + guard span 观测 + BLOCK/CONFIRM/ALLOW 分流），
 * 执行小步委托 {@link ToolCallExecutor}（占位符解析 + 调用 + 限长 + 参数转换错误兜底）。
 * </p>
 */
@Slf4j
public class GuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String conversationId;
    private final Long userId;
    private final ToolDefinitionProvider toolDefinitionProvider;
    /** 决策小步：guard 评估 + 观测 + 分流 */
    private final ToolGuardGate guardGate;
    /** 执行小步：ALLOW 放行后的工具调用 + 限长 */
    private final ToolCallExecutor toolCallExecutor;

    private static final AtomicInteger invokeCounter = new AtomicInteger(0);

    public GuardedToolCallback(ToolCallback delegate, ToolGuardManager guardManager,
                               String conversationId, Long userId, boolean returnDirect,
                               AgentTracer agentTracer) {
        this(delegate, guardManager, conversationId, userId, returnDirect, agentTracer, true,
                ToolCallback::getToolDefinition, 1200, null, null);
    }

    public GuardedToolCallback(ToolCallback delegate, ToolGuardManager guardManager,
                               String conversationId, Long userId, boolean returnDirect,
                               AgentTracer agentTracer, boolean approvalEnabled,
                               ToolDefinitionProvider toolDefinitionProvider, int maxResultChars) {
        this(delegate, guardManager, conversationId, userId, returnDirect, agentTracer, approvalEnabled,
                toolDefinitionProvider, maxResultChars, null, null);
    }

    /** 主构造：额外携带实际模型名与参数脱敏器（ToolBeanCollector 组装时注入，用于 guard span 名编码） */
    public GuardedToolCallback(ToolCallback delegate, ToolGuardManager guardManager,
                               String conversationId, Long userId, boolean returnDirect,
                               AgentTracer agentTracer, boolean approvalEnabled,
                               ToolDefinitionProvider toolDefinitionProvider, int maxResultChars,
                               String modelName, AttributeSanitizer sanitizer) {
        this.delegate = delegate;
        this.conversationId = conversationId;
        this.userId = userId;
        this.toolDefinitionProvider = toolDefinitionProvider;
        this.guardGate = new ToolGuardGate(guardManager, agentTracer, approvalEnabled, returnDirect,
                modelName, sanitizer);
        this.toolCallExecutor = new ToolCallExecutor(delegate, maxResultChars, returnDirect);
    }

    public String getToolName() {
        return getToolDefinition().name();
    }

    public String getToolDescription() {
        return getToolDefinition().description();
    }

    public ToolDefinition getToolDefinition() {
        return toolDefinitionProvider.resolve(delegate);
    }

    /**
     * 原始工具名（注解定义，不触发外置描述解析）。
     * 按名过滤/校验场景只需 name，name 永远来自注解，无需走 Langfuse。
     */
    public String getRawToolName() {
        return delegate.getToolDefinition().name();
    }

    /**
     * 原始工具描述（注解定义，不触发外置描述解析）。
     * 规划阶段构建工具清单时用，避免为全部工具拉取外置描述。
     */
    public String getRawToolDescription() {
        return delegate.getToolDefinition().description();
    }

    /** 取任意 ToolCallback 的原始名（非 Guarded 回调直接走其定义） */
    public static String rawName(ToolCallback cb) {
        if (cb instanceof GuardedToolCallback g) {
            return g.getRawToolName();
        }
        return cb.getToolDefinition().name();
    }

    /** 取任意 ToolCallback 的原始描述（非 Guarded 回调直接走其定义） */
    public static String rawDescription(ToolCallback cb) {
        if (cb instanceof GuardedToolCallback g) {
            return g.getRawToolDescription();
        }
        return cb.getToolDefinition().description();
    }

    /**
     * 原始工具输入 schema（注解定义，不触发外置描述解析）。
     * 规划阶段提取参数名（properties key）用。
     */
    public String getRawInputSchema() {
        return delegate.getToolDefinition().inputSchema();
    }

    /** 取任意 ToolCallback 的原始输入 schema（非 Guarded 回调直接走其定义） */
    public static String getRawInputSchema(ToolCallback cb) {
        if (cb instanceof GuardedToolCallback g) {
            return g.getRawInputSchema();
        }
        return cb.getToolDefinition().inputSchema();
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

        if (toolContext.getContext() != null) {
            Object uid = toolContext.getContext().get("userId");
            if (uid instanceof Long) {
                effectiveUserId = (Long) uid;
            }
        }

        ToolInvocationContext context = ToolInvocationContext.builder()
                .toolName(toolName)
                .arguments(functionPayload)
                .conversationId(effectiveConversationId(toolContext))
                .userId(effectiveUserId)
                .invocationCount(invokeCounter.incrementAndGet())
                .build();

        // lambda 捕获需要 effectively final 副本（toolContext/effectiveUserId 上文可能被重赋值）
        final ToolContext ctxForExec = toolContext;
        final Long uidForExec = effectiveUserId;
        return guardGate.run(context, toolName, functionPayload,
                () -> toolCallExecutor.execute(functionPayload, ctxForExec, uidForExec));
    }

    /**
     * 绕过守卫直调底层工具（仅审批恢复路径使用：工具已被用户确认，不再二次投票）。
     * <p>
     * 需要显式通过 ToolContext 传递 userId / conversationId，
     * 因为恢复执行在异步线程、无 UserHolder，且数据权限切面从 ToolContext 取 userId。
     * </p>
     */
    public String callBypass(String functionPayload, ToolContext toolContext) {
        if (toolContext == null) {
            toolContext = new ToolContext(Map.of());
        }
        return toolCallExecutor.executeBypass(functionPayload, toolContext);
    }

    /**
     * 会话 ID 解析顺序：ToolContext（executor 运行时注入）→ AgentContextHolder
     * （请求级上下文，异步边界由 Propagator 传播）→ 构造时冻结值（仅最后防线）。
     */
    private String effectiveConversationId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object cid = toolContext.getContext().get("conversationId");
            if (cid instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        AgentContext ctx = AgentContextHolder.get();
        if (ctx != null && ctx.conversationId() != null && !ctx.conversationId().isBlank()) {
            return ctx.conversationId();
        }
        return conversationId;
    }
}
