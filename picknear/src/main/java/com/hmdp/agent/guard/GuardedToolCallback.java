package com.hmdp.agent.guard;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.tool.ToolDefinitionProvider;
import com.hmdp.agent.util.TextUtils;
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
    private final boolean approvalEnabled;
    private final ToolDefinitionProvider toolDefinitionProvider;
    private final int maxResultChars;
    /** 实际运行的模型名（如 qwen-plus-2025-07-28），编码进 guard span 名；null 时省略该段 */
    private final String modelName;
    /** 参数脱敏器（参数摘要写入 span 名前统一出口：手机号/邮箱/身份证 + 限长） */
    private final AttributeSanitizer sanitizer;

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
        this.guardManager = guardManager;
        this.conversationId = conversationId;
        this.userId = userId;
        this.returnDirect = returnDirect;
        this.agentTracer = agentTracer;
        this.approvalEnabled = approvalEnabled;
        this.toolDefinitionProvider = toolDefinitionProvider;
        this.maxResultChars = maxResultChars;
        this.modelName = modelName;
        this.sanitizer = sanitizer;
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

        GuardResult result = guardManager.evaluate(context);

        // 观测：agent.guard.{decision}.{toolName}[.{model}][.{参数摘要}]（M1.5 规则：决策进 span 名；
        // Langfuse 不展示自定义属性，模型名与参数摘要编码进 span 名，控制台免点击直读执行内容）
        try (AgentSpan guardSpan = agentTracer.start(AgentSpanSpec.GUARD,
                buildGuardSemantic(result.getDecision().name(), toolName, functionPayload))) {
            // Fail-Open：tracer 不可用（mock/null）时 guard 块仍正常执行
            if (guardSpan != null) {
                guardSpan.attribute("tool.name", toolName);
                if (modelName != null && !modelName.isBlank()) {
                    guardSpan.attribute("model.name", modelName);
                }
                if (functionPayload != null && !functionPayload.isBlank()) {
                    guardSpan.attribute("tool.arguments", functionPayload);
                }
                guardSpan.attribute("guard.policy", result.getPolicyName() != null
                        ? result.getPolicyName() : "none");
            }

            switch (result.getDecision()) {
                case BLOCK -> {
                    String msg = result.getReason() != null
                            ? result.getReason()
                            : "操作已被安全策略拦截";
                    log.warn("工具调用被拦截 [tool={}, policy={}]", toolName, result.getPolicyName());
                    return returnDirect ? msg : "{\"error\":\"" + msg + "\"}";
                }
                case CONFIRM -> {
                    String msg = result.getReason() != null
                            ? result.getReason()
                            : "该操作需要你的确认才能执行";
                    log.info("工具调用需确认 [tool={}, policy={}]", toolName, result.getPolicyName());
                    // 审批开启 → 抛异常触发真暂停（TaskPlanner 捕获后建审批记录）；
                    // 关闭 → 退回旧行为：把确认提示当工具结果返回给 LLM 自行处理
                    if (approvalEnabled) {
                        throw new ConfirmRequiredException(context, msg, result.getPolicyName());
                    }
                    return returnDirect ? msg : "{\"confirm\":\"" + msg + "\"}";
                }
                case ALLOW -> {
                    log.debug("工具调用放行 [tool={}]", toolName);
                    return limitToolResult(delegate.call(functionPayload, toolContext));
                }
            }
        }
        return delegate.call(functionPayload, toolContext);
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
        return limitToolResult(delegate.call(functionPayload, toolContext));
    }

    /**
     * 工具结果在回灌进 LLM 消息历史前截断至 {@link #maxResultChars}（codepoint-safe），
     * 防上下文膨胀。guard 自身生成的 BLOCK/CONFIRM 消息不经过此方法。
     */
    private String limitToolResult(String result) {
        // 取证（设计文档 §8.1）：记录工具原始返回长度，用于对照 trace 定位输入膨胀来源
        if (log.isDebugEnabled()) {
            log.debug("[Guard] 工具结果原始长度={} (maxResultChars={}, tool={})",
                    result != null ? result.length() : 0, maxResultChars,
                    getRawToolName());
        }
        return TextUtils.truncate(result, maxResultChars);
    }

    /**
     * 优先从 ToolContext 读取会话 ID（运行时由 executor 注入真实会话），
     * 否则回退构造时冻结的默认值（启动 UUID，仅兜底）。
     */
    private String effectiveConversationId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object cid = toolContext.getContext().get("conversationId");
            if (cid instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return conversationId;
    }

    /** 参数摘要入 span 名的最大字符数（防 span 名膨胀；也是控制台免点击直读的关键载荷） */
    private static final int ARGS_MAX_CHARS = 40;

    /**
     * 组装 guard span 语义：{decision}.{toolName}[.{model}][.{参数摘要}]。
     * 模型名与参数摘要均为可选项（构造未注入/参数为空时省略），核心前缀 agent.guard. 不变，
     * 观测白名单（agent.）与统计（agent.guard）不受影响。
     */
    private String buildGuardSemantic(String decision, String toolName, String payload) {
        StringBuilder sb = new StringBuilder(decision).append('.').append(toolName);
        if (modelName != null && !modelName.isBlank()) {
            sb.append('.').append(modelName);
        }
        String args = compactArgs(payload);
        if (!args.isBlank()) {
            sb.append('.').append(args);
        }
        return sb.toString();
    }

    /**
     * 工具参数 → 紧凑摘要：去 JSON 引号/空白 → 脱敏（手机号/邮箱/身份证）→ 限长。
     * 用于 span 名编码（Langfuse 不展示自定义属性）；空/null 参数或 {@code {}} 返回 ""。
     */
    private String compactArgs(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        String cleaned = payload.replaceAll("[\\p{Cntrl}\\s\"]", "");
        if (cleaned.isBlank() || "{}".equals(cleaned)) {
            return "";
        }
        String masked = sanitizer != null ? sanitizer.sanitizeSummary(cleaned) : cleaned;
        return TextUtils.truncate(masked, ARGS_MAX_CHARS);
    }
}
