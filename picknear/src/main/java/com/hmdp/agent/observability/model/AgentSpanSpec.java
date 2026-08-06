package com.hmdp.agent.observability.model;

/**
 * Agent 业务 span 类型定义（M2 埋点注册表）。
 * <p>
 * span 命名规则（M1.5 实测落地）：{@link #NAMESPACE}{type}[.{semantic}]
 * ——Langfuse 4.2.0 JP 云版 OTLP 转译不展示自定义 attributes，关键业务语义
 * 必须写进 span 名才能在其 UI 可见（如 {@code agent.tool_call.queryShop}、
 * {@code agent.guard.BLOCK.deleteBlog}）。类型统计仍按前缀匹配，不受语义后缀影响。
 * </p>
 */
public enum AgentSpanSpec {

    /** 会话根 span（ChatController 入口，整棵 trace 的根） */
    SESSION("session"),
    /** Prompt Hook 链执行 */
    PROMPT_HOOK("prompt_hook"),
    /** Phase 1 流式 LLM 调用 */
    PHASE1("phase1"),
    /** AfterAiHook 决策（PASS/REPLACE/BLOCK/PLANNING） */
    DECISION("decision"),
    /** TaskPlanner 主循环每轮（semantic=轮次号） */
    ROUND("round"),
    /** decompose 规划产出（属性含 plan_json 诊断） */
    PLAN("plan"),
    /** SubTaskAgent.execute 整段（默认路径） */
    SUBAGENT("subagent"),
    /** 单个 TOOL_CALL 执行（semantic=工具名，回退路径） */
    TOOL_CALL("tool_call"),
    /** LLM_REASON 执行 */
    LLM_REASON("llm_reason"),
    /** Guard 评估（semantic=决策.工具名，如 BLOCK.deleteBlog） */
    GUARD("guard"),
    /** Prompt 模板获取/渲染（semantic=模板键，如 agent.system.main） */
    PROMPT("prompt"),
    /** SSE 事件（semantic=finish 状态 COMPLETE/TIMEOUT/ERROR） */
    SSE("sse");

    /** 业务 span 命名空间（观测白名单默认前缀，见 support.ObservabilityTraceFilter） */
    public static final String NAMESPACE = "agent.";

    private final String type;

    AgentSpanSpec(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    /**
     * 生成 span 名：{@code agent.{type}[.{semantic}]}。
     *
     * @param semantic 业务语义后缀（工具名/决策/轮次等），可为 null/blank
     * @return 如 {@code agent.tool_call.queryShop}
     */
    public String spanName(String semantic) {
        if (semantic == null || semantic.isBlank()) {
            return NAMESPACE + type;
        }
        return NAMESPACE + type + "." + semantic;
    }
}
