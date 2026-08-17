package com.hmdp.agent.observability.model;

import com.hmdp.agent.observability.support.SanitizeLevel;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hmdp.agent.observability.support.SanitizeLevel.DIAGNOSTIC;
import static com.hmdp.agent.observability.support.SanitizeLevel.SUMMARY;

/**
 * 上报字段注册表（唯一真相源）：一个字段常量同时编码 {@code key} + 脱敏级别 + 所属 span 类型。
 * <p>
 * 埋点方通过 {@code span.set(AgentField.X, value)} 写属性，不再写字符串 key、不再选脱敏方法。
 * 改字段名 / 加字段 / 改脱敏级别都只动本枚举一处。
 * </p>
 * <p>
 * 两类字段：
 * <ul>
 *   <li><b>固定字段</b>：{@code key()} 直接返回 key 字符串，如 {@code CONVERSATION_ID}</li>
 *   <li><b>参数化字段</b>：key 模板含 {@code {段}} 占位符，{@code key(segment...)} 按序填充，
 *       支持动态 key（如 {@code tool.{i}.name} 逐工具回填、{@code guard.policy.{name}} 逐策略平铺）</li>
 * </ul>
 * </p>
 * <p>
 * 约定：改动本枚举的提交必须同步架构文档 §5.1 字段表（见 md/agent/observability/）。
 * </p>
 */
public enum AgentField {

    // —— SESSION（会话根 span）——
    CONVERSATION_ID("conversation.id", SUMMARY, AgentSpanSpec.SESSION),
    USER_ID("user.id", SUMMARY, AgentSpanSpec.SESSION),
    /** SSE 会话收尾原因（ObservedSseEmitter 收敛点，COMPLETE/TIMEOUT/ERROR） */
    FINISH("finish", SUMMARY, AgentSpanSpec.SESSION),

    // —— PROMPT_HOOK（Prompt Hook 链）——
    HOOK_DECISION("hook.decision", SUMMARY, AgentSpanSpec.PROMPT_HOOK),
    /** 复用于 DECISION span */
    HOOK_NAME("hook.name", SUMMARY, AgentSpanSpec.PROMPT_HOOK, AgentSpanSpec.DECISION),

    // —— PHASE1（Phase 1 流式 LLM 调用）——
    ATTEMPT("attempt", SUMMARY, AgentSpanSpec.PHASE1),
    STREAM_LEN("stream_len", SUMMARY, AgentSpanSpec.PHASE1),

    // —— DECISION（AfterAiHook 决策）——
    DECISION("decision", SUMMARY, AgentSpanSpec.DECISION),

    // —— ROUND / SUBAGENT（TaskPlanner 主循环）——
    TOOL_COUNT("tool_count", SUMMARY, AgentSpanSpec.ROUND, AgentSpanSpec.SUBAGENT),
    PLAN_VALID("plan_valid", SUMMARY, AgentSpanSpec.ROUND),

    // —— PLAN（decompose 规划产出）——
    VALIDATE_RESULT("validate_result", SUMMARY, AgentSpanSpec.PLAN),
    PLAN_TOOLS("plan.tools", SUMMARY, AgentSpanSpec.PLAN),

    // —— TOOL_CALL / LLM_REASON（TaskExecutor）——
    TOOL_RESULT_SUMMARY("tool.result_summary", SUMMARY, AgentSpanSpec.TOOL_CALL),
    /** 复用于 GUARD span */
    TOOL_NAME("tool.name", SUMMARY, AgentSpanSpec.TOOL_CALL, AgentSpanSpec.GUARD),
    BASED_ON("based_on", SUMMARY, AgentSpanSpec.LLM_REASON),

    // —— GUARD（工具调用守卫）——
    MODEL_NAME("model.name", SUMMARY, AgentSpanSpec.GUARD),
    TOOL_ARGUMENTS("tool.arguments", SUMMARY, AgentSpanSpec.GUARD),
    GUARD_POLICY("guard.policy", SUMMARY, AgentSpanSpec.GUARD),
    /** 守卫决策（BLOCK/CONFIRM/ALLOW）——语义数据进属性（换后端靠属性展示时的兜底，2026-08-17 增补） */
    GUARD_DECISION("guard.decision", SUMMARY, AgentSpanSpec.GUARD),

    // —— PROMPT（Prompt 模板获取/渲染）——
    PROMPT_SOURCE("prompt.source", SUMMARY, AgentSpanSpec.PROMPT),
    PROMPT_RENDERED_LEN("prompt.rendered_len", SUMMARY, AgentSpanSpec.PROMPT),

    // —— 通用 ——
    STATUS("status", SUMMARY, AgentSpanSpec.SESSION, AgentSpanSpec.PROMPT_HOOK, AgentSpanSpec.PHASE1,
            AgentSpanSpec.DECISION, AgentSpanSpec.ROUND, AgentSpanSpec.PLAN, AgentSpanSpec.SUBAGENT,
            AgentSpanSpec.TOOL_CALL, AgentSpanSpec.LLM_REASON, AgentSpanSpec.GUARD),

    // —— 参数化字段（key 模板含 {段} 占位符，运行时填充）——
    /** 逐工具回填名（M4 规划，本次仅注册模板） */
    TOOL_ENTRY_NAME("tool.{i}.name", SUMMARY, AgentSpanSpec.SUBAGENT),
    /** 逐工具回填状态（M4 规划，本次仅注册模板） */
    TOOL_ENTRY_STATUS("tool.{i}.status", SUMMARY, AgentSpanSpec.SUBAGENT),
    /** 逐策略平铺（M4 规划，本次仅注册模板） */
    GUARD_POLICY_ENTRY("guard.policy.{name}", SUMMARY, AgentSpanSpec.GUARD);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    private final String keyTemplate;
    private final SanitizeLevel level;
    private final AgentSpanSpec[] specs;

    AgentField(String keyTemplate, SanitizeLevel level, AgentSpanSpec... specs) {
        this.keyTemplate = keyTemplate;
        this.level = level;
        this.specs = specs;
    }

    /** 固定 key（无占位符字段直接用；参数化字段返回模板原文） */
    public String key() {
        return keyTemplate;
    }

    /**
     * 参数化 key：按序把 {@code segments} 填入 {@code {段}} 占位符。
     * 段数与占位符数不一致抛 {@link IllegalArgumentException}。
     *
     * @param segments 运行时段值（如 "0"、"RateLimit"）
     * @return 填充后的完整 key，如 {@code tool.0.name}
     */
    public String key(String... segments) {
        if (segments == null || segments.length == 0) {
            return keyTemplate;
        }
        long placeholderCount = PLACEHOLDER.matcher(keyTemplate).results().count();
        if (segments.length != placeholderCount) {
            throw new IllegalArgumentException(
                    "字段 " + name() + " 占位符数=" + placeholderCount + " 与段数=" + segments.length + " 不匹配");
        }
        Matcher matcher = PLACEHOLDER.matcher(keyTemplate);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(segments[i++]));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 按完整 key 反查固定字段（参数化字段的运行时 key 无法枚举，不命中） */
    public static Optional<AgentField> byKey(String key) {
        return Arrays.stream(values())
                .filter(f -> f.keyTemplate.equals(key))
                .findFirst();
    }

    public SanitizeLevel level() {
        return level;
    }

    /** 字段归属的 span 类型（纯文档/校验用，运行时不强制） */
    public AgentSpanSpec[] specs() {
        return specs;
    }
}
