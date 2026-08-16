package com.hmdp.agent.guard;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.util.TextUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 守卫门（GuardedToolCallback 拆分：决策小步）。
 * <p>
 * 职责：汇总策略投票（{@link ToolGuardManager}）→ guard span 观测（决策/工具名/
 * 模型名/参数摘要编码进 span 名）→ 按决策分流：
 * <ul>
 *   <li>BLOCK → 拦截消息（returnDirect 时纯文本，否则 {@code {"error":...}} JSON）；</li>
 *   <li>CONFIRM → 审批开启抛 {@link ConfirmRequiredException} 触发真暂停；
 *       关闭退回确认提示 JSON 给 LLM 自行处理；</li>
 *   <li>ALLOW → 执行调用方传入的放行动作并返回其结果。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class ToolGuardGate {

    private final ToolGuardManager guardManager;
    private final AgentTracer agentTracer;
    private final boolean approvalEnabled;
    private final boolean returnDirect;
    /** 实际运行的模型名（如 qwen-plus-2025-07-28），编码进 guard span 名；null 时省略该段 */
    private final String modelName;
    /** 参数脱敏器（参数摘要写入 span 名前统一出口：手机号/邮箱/身份证 + 限长） */
    private final AttributeSanitizer sanitizer;

    public ToolGuardGate(ToolGuardManager guardManager, AgentTracer agentTracer,
                         boolean approvalEnabled, boolean returnDirect,
                         String modelName, AttributeSanitizer sanitizer) {
        this.guardManager = guardManager;
        this.agentTracer = agentTracer;
        this.approvalEnabled = approvalEnabled;
        this.returnDirect = returnDirect;
        this.modelName = modelName;
        this.sanitizer = sanitizer;
    }

    /**
     * 守卫入口：评估策略 → guard span 观测 → 决策处理。
     *
     * @param context       工具调用上下文（含 userId/会话/调用计数）
     * @param toolName      工具名（span 字段与错误消息用）
     * @param payload       原始参数 JSON（span 名编码用）
     * @param allowedAction ALLOW 放行后的执行动作（ToolCallExecutor.execute）
     * @return 工具结果字符串（ALLOW 时来自 allowedAction；BLOCK/CONFIRM 为拦截/确认消息）
     * @throws ConfirmRequiredException 审批开启且决策为 CONFIRM 时
     */
    public String run(ToolInvocationContext context, String toolName, String payload,
                      Supplier<String> allowedAction) {
        GuardResult result = guardManager.evaluate(context);

        // 观测：agent.guard.{decision}.{toolName}[.{model}][.{参数摘要}]（M1.5 规则：决策进 span 名；
        // Langfuse 不展示自定义属性，模型名与参数摘要编码进 span 名，控制台免点击直读执行内容）
        try (AgentSpan guardSpan = agentTracer.start(AgentSpanSpec.GUARD,
                buildGuardSemantic(result.getDecision().name(), toolName, payload))) {
            // Fail-Open：tracer 不可用（mock/null）时 guard 块仍正常执行
            if (guardSpan != null) {
                guardSpan.set(AgentField.TOOL_NAME, toolName);
                if (modelName != null && !modelName.isBlank()) {
                    guardSpan.set(AgentField.MODEL_NAME, modelName);
                }
                if (payload != null && !payload.isBlank()) {
                    guardSpan.set(AgentField.TOOL_ARGUMENTS, payload);
                }
                guardSpan.set(AgentField.GUARD_POLICY, result.getPolicyName() != null
                        ? result.getPolicyName() : "none");
            }

            return switch (result.getDecision()) {
                case BLOCK -> {
                    String msg = result.getReason() != null
                            ? result.getReason()
                            : "操作已被安全策略拦截";
                    log.warn("工具调用被拦截 [tool={}, policy={}]", toolName, result.getPolicyName());
                    yield returnDirect ? msg : "{\"error\":\"" + msg + "\"}";
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
                    yield returnDirect ? msg : "{\"confirm\":\"" + msg + "\"}";
                }
                case ALLOW -> {
                    log.debug("工具调用放行 [tool={}]", toolName);
                    yield allowedAction.get();
                }
            };
        }
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
