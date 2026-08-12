package com.hmdp.agent.observability.api;

import com.hmdp.agent.observability.core.AgentSpanImpl;
import com.hmdp.agent.observability.core.SpanLifecycle;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.observability.model.SpanContext;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.observability.support.TraceProperties;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 观测门面（MVC 分层中的 api 层，埋点方唯一入口）。
 * <p>
 * 依赖方向：主链路（ChatController/AiServiceImpl/TaskPlanner/SubTaskAgent）
 * 只 import 本类与 {@link AgentSpanSpec} / {@link SpanContext}，不触碰 core/support 内部。
 * </p>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>无状态单例：父子关系由 Micrometer 在创建时从当前线程 scope 栈捕获，
 *       无需按会话保存状态（多会话并发安全）</li>
 *   <li>Fail-Open（架构文档 §6.1）：埋点自身异常吞掉并告警日志，不影响主链路，
 *       此时返回 {@link NoopAgentSpan}</li>
 *   <li>跨线程：根 span 经 {@link SpanContext} 显式传递，异步线程入口
 *       {@code try (Scope s = tracer.resume(ctx)) { ... }} 重新挂载</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class AgentTracer {

    /** 语义后缀最大长度（防 span 名膨胀/注入） */
    private static final int SEMANTIC_MAX_CHARS = 64;

    private final SpanLifecycle lifecycle;
    private final AttributeSanitizer sanitizer;
    /** 业务埋点总开关（hmdp.ai-observability.trace-enabled，false 时整门面 Noop 空转，省 Langfuse 配额） */
    private final boolean traceEnabled;

    public AgentTracer(SpanLifecycle lifecycle, AttributeSanitizer sanitizer, TraceProperties props) {
        this.lifecycle = lifecycle;
        this.sanitizer = sanitizer;
        this.traceEnabled = props.isTraceEnabled();
    }

    /**
     * 会话入口：创建根 span（agent.session），返回根句柄由 Controller 持有，
     * 整棵 trace 的结束唯一收敛点（三回调/方法 finally 调用 {@code root.end()}）。
     */
    public AgentSpan startSession(String conversationId, String userId) {
        if (!traceEnabled) {
            return NoopAgentSpan.INSTANCE;
        }
        try {
            AgentSpan root = start(AgentSpanSpec.SESSION, null);
            if (conversationId != null) {
                root.set(AgentField.CONVERSATION_ID, conversationId);
            }
            if (userId != null) {
                root.set(AgentField.USER_ID, userId);
            }
            return root;
        } catch (Exception e) {
            log.warn("[observability] startSession 失败，降级 Noop（Fail-Open）", e);
            return NoopAgentSpan.INSTANCE;
        }
    }

    /**
     * 埋点：创建并 start 一个业务 span（父子关系自动从当前线程 scope 栈捕获）。
     *
     * @param spec     span 类型（注册表）
     * @param semantic 业务语义后缀（如工具名/决策/轮次），span 名 = agent.{type}[.{semantic}]
     */
    public AgentSpan start(AgentSpanSpec spec, String semantic) {
        if (!traceEnabled) {
            return NoopAgentSpan.INSTANCE;
        }
        try {
            String name = sanitizeName(spec.spanName(semantic));
            Observation observation = lifecycle.create(name);
            // 断链修复（2026-08-03）：必须先 start 后 openScope！
            // openScope（onScopeOpened）会用 computeIfAbsent 把空 TracingContext 塞进 context，
            // 若先 openScope，onStart 时 getParentSpan 命中"自己已有 TracingContext"分支，
            // 返回自己的空 span → 无视父级 → 每个 span 新开 traceId。
            observation.start();
            Observation.Scope scope = lifecycle.openScope(observation);
            return new AgentSpanImpl(observation, scope, sanitizer);
        } catch (Exception e) {
            log.warn("[observability] start 失败 span={}，降级 Noop（Fail-Open）", spec, e);
            return NoopAgentSpan.INSTANCE;
        }
    }

    /**
     * 异步线程入口：重新 openScope 根 span，返回的 Scope 必须 try-with-resources 配对
     * （时序契约 T2）；之后 {@link #start} 创建的 span 自动挂到根下。
     */
    public Observation.Scope resume(SpanContext context) {
        return context.rootSpan().openScope();
    }

    /**
     * 便捷重载：根 span 直接 resume（ChatContext 携带 AgentSpan 时用）。
     */
    public Observation.Scope resume(AgentSpan rootSpan) {
        return rootSpan.openScope();
    }

    /**
     * span 名清洗：去空白/控制字符（防注入与膨胀），整体限长。
     */
    private String sanitizeName(String name) {
        String cleaned = name.replaceAll("[\\p{Cntrl}\\s]", "");
        return cleaned.length() <= SEMANTIC_MAX_CHARS + 32 ? cleaned : cleaned.substring(0, SEMANTIC_MAX_CHARS + 32);
    }
}
