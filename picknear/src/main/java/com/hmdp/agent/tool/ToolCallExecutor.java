package com.hmdp.agent.tool;

import com.hmdp.agent.plan.support.UserIdPlaceholderResolver;
import com.hmdp.agent.util.TextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * 守卫放行后的工具执行器（GuardedToolCallback 拆分：执行小步）。
 * <p>
 * 职责：self 占位符最后一层解析 → 委托底层 {@link ToolCallback} → 结果限长
 * （防上下文膨胀）；参数类型转换失败（LLM 传非数字）转友好错误返回给 LLM 自纠，
 * 而非让晦涩异常进上下文/日志。
 * </p>
 */
@Slf4j
public class ToolCallExecutor {

    private final ToolCallback delegate;
    private final int maxResultChars;
    private final boolean returnDirect;
    /** 底层工具名（错误消息/观测日志用，构造时冻结） */
    private final String toolName;

    public ToolCallExecutor(ToolCallback delegate, int maxResultChars, boolean returnDirect) {
        this.delegate = delegate;
        this.maxResultChars = maxResultChars;
        this.returnDirect = returnDirect;
        this.toolName = delegate.getToolDefinition().name();
    }

    /**
     * ALLOW 分支执行：self 占位符最后一层解析（覆盖子 Agent 内部再编造；
     * 快照恢复走 {@link #executeBypass}）→ 委托调用 → 限长。
     */
    public String execute(String payload, ToolContext toolContext, Long effectiveUserId) {
        try {
            String resolvedPayload = UserIdPlaceholderResolver.resolvePayload(
                    payload, toolName, effectiveUserId);
            return limitToolResult(delegate.call(resolvedPayload, toolContext));
        } catch (RuntimeException e) {
            // 参数类型转换失败（LLM 给数字参数传了非数字，如 userId="s"）：
            // Spring AI 对 Long/Integer 参数经 new BigDecimal(string) 转换，抛 NumberFormatException。
            // 转成友好错误返回给 LLM 自纠，而不是让晦涩异常进上下文/日志。
            if (isParamConversionError(e)) {
                String msg = "参数格式错误：" + toolName + " 的数字型参数必须是数字，请检查参数后重试";
                log.warn("工具参数转换失败 [tool={}, err={}]", toolName, e.getMessage());
                return returnDirect ? msg : "{\"error\":\"" + msg + "\"}";
            }
            throw e;
        }
    }

    /**
     * 绕过守卫直调底层工具（仅审批恢复路径使用：工具已被用户确认，不再二次投票）。
     * <p>
     * 需要显式通过 ToolContext 传递 userId / conversationId，
     * 因为恢复执行在异步线程、无 UserHolder，且数据权限切面从 ToolContext 取 userId。
     * </p>
     */
    public String executeBypass(String payload, ToolContext toolContext) {
        // 快照恢复路径参数未过 validatePlan，同样做 self 占位符解析（userId 由 TaskPlanner 注入 ToolContext）
        String resolvedPayload = UserIdPlaceholderResolver.resolvePayload(
                payload, toolName, userIdFromContext(toolContext));
        return limitToolResult(delegate.call(resolvedPayload, toolContext));
    }

    /** 从 ToolContext 提取 userId（无则 null） */
    private static Long userIdFromContext(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object uid = toolContext.getContext().get("userId");
            if (uid instanceof Long l) {
                return l;
            }
        }
        return null;
    }

    /**
     * 工具结果在回灌进 LLM 消息历史前截断至 {@link #maxResultChars}（codepoint-safe），
     * 防上下文膨胀。guard 自身生成的 BLOCK/CONFIRM 消息不经过此方法。
     */
    private String limitToolResult(String result) {
        // 取证（设计文档 §8.1）：记录工具原始返回长度，用于对照 trace 定位输入膨胀来源
        if (log.isDebugEnabled()) {
            log.debug("[Guard] 工具结果原始长度={} (maxResultChars={}, tool={})",
                    result != null ? result.length() : 0, maxResultChars, toolName);
        }
        return TextUtils.truncate(result, maxResultChars);
    }

    /**
     * 判断异常链是否为参数类型转换失败：LLM 给数字参数传了非数字值（如 userId="s"），
     * Spring AI 对 Long/Integer 参数经 {@code new BigDecimal(string)} 转换抛 {@link NumberFormatException}，
     * 并被包成 {@code ToolExecutionException}。沿 cause 链找 NumberFormatException 即可命中。
     */
    private static boolean isParamConversionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NumberFormatException) {
                return true;
            }
        }
        return false;
    }
}
