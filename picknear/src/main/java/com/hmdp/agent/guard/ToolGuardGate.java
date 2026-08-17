package com.hmdp.agent.guard;

import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.guard.model.GuardResult;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 守卫门（GuardedToolCallback 拆分：决策小步）。
 * <p>
 * 职责：汇总策略投票（{@link ToolGuardManager}）→ guard span 观测（决策/工具名/
 * 模型名/参数/策略全量进属性；span 名语义编码与否由观测后端能力驱动）→ 按决策分流：
 * <ul>
 *   <li>BLOCK → 拦截消息（returnDirect 时纯文本，否则 {@code {"error":...}} JSON）；</li>
 *   <li>CONFIRM → 审批开启抛 {@link ConfirmRequiredException} 触发真暂停；
 *       关闭退回确认提示 JSON 给 LLM 自行处理；</li>
 *   <li>ALLOW → 执行调用方传入的放行动作并返回其结果。</li>
 * </ul>
 * </p>
 * <p>
 * 观测边界（2026-08-17，观测后端解耦方案评审 13.3.2）：<b>语义内容留业务侧、加工格式沉策略侧</b>
 * ——本类只传原始语义（decision/toolName/modelName/payload）给
 * {@link AgentTracer#startGuard}；span 名拼法/参数紧凑化/脱敏/限长全部在
 * {@code observability.core.SpanNameEncoder}，本类不再持有任何载荷加工逻辑。
 * </p>
 */
@Slf4j
public class ToolGuardGate {

    private final ToolGuardManager guardManager;
    private final AgentTracer agentTracer;
    private final boolean approvalEnabled;
    private final boolean returnDirect;
    /** 实际运行的模型名（guard 原始语义与 MODEL_NAME 属性共用；null 时省略） */
    private final String modelName;

    public ToolGuardGate(ToolGuardManager guardManager, AgentTracer agentTracer,
                         boolean approvalEnabled, boolean returnDirect,
                         String modelName) {
        this.guardManager = guardManager;
        this.agentTracer = agentTracer;
        this.approvalEnabled = approvalEnabled;
        this.returnDirect = returnDirect;
        this.modelName = modelName;
    }

    /**
     * 守卫入口：评估策略 → guard span 观测 → 决策处理。
     *
     * @param context       工具调用上下文（含 userId/会话/调用计数）
     * @param toolName      工具名（span 字段与错误消息用）
     * @param payload       原始参数 JSON（span 字段与命名编码用）
     * @param allowedAction ALLOW 放行后的执行动作（ToolCallExecutor.execute）
     * @return 工具结果字符串（ALLOW 时来自 allowedAction；BLOCK/CONFIRM 为拦截/确认消息）
     * @throws ConfirmRequiredException 审批开启且决策为 CONFIRM 时
     */
    public String run(ToolInvocationContext context, String toolName, String payload,
                      Supplier<String> allowedAction) {
        GuardResult result = guardManager.evaluate(context);

        // 观测：guard span（语义数据全量进属性；span 名编码由命名策略按后端能力决定）
        try (AgentSpan guardSpan = agentTracer.startGuard(
                result.getDecision().name(), toolName, modelName, payload)) {
            // Fail-Open：tracer 不可用（mock/null）时 guard 块仍正常执行
            if (guardSpan != null) {
                guardSpan.set(AgentField.GUARD_DECISION, result.getDecision().name());
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
}