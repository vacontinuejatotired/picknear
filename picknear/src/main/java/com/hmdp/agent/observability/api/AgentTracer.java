package com.hmdp.agent.observability.api;

import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.backend.TraceBackendAssembler;
import com.hmdp.agent.observability.backend.impl.LangfuseBackend;
import com.hmdp.agent.observability.core.AgentSpanImpl;
import com.hmdp.agent.observability.core.SpanLifecycle;
import com.hmdp.agent.observability.core.SpanNameEncoder;
import com.hmdp.agent.observability.core.SpanNamingStrategy;
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
 *   <li>命名策略（2026-08-17，观测后端解耦方案 S3）：span 名是否编码语义后缀由
 *       观测后端能力推导（{@link SpanNamingStrategy}）；guard 载荷加工在
 *       {@link SpanNameEncoder}，业务侧只传原始语义</li>
 *   <li>平台关联属性：startSession 按 {@code backend.associationAttributes()} 额外写
 *       平台别名（如 langfuse.user.id），值复用会话参数（评审 13.2.4 MVP）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class AgentTracer {

    private final SpanLifecycle lifecycle;
    private final AttributeSanitizer sanitizer;
    /** 业务埋点总开关（hmdp.ai-observability.trace-enabled，false 时整门面 Noop 空转） */
    private final boolean traceEnabled;
    private final TraceBackend backend;
    private final SpanNamingStrategy strategy;

    /**
     * Spring 主构造：后端由装配器按 {@code hmdp.ai-observability.backend.type} 解析
     * （type 缺省 → Langfuse，兼容现状；Fail-Open 分级见装配器）。
     */
    public AgentTracer(SpanLifecycle lifecycle, AttributeSanitizer sanitizer, TraceProperties props,
                       TraceBackendAssembler backendAssembler, SpanNameEncoder encoder) {
        this(lifecycle, sanitizer, props, backendAssembler.assemble(), encoder);
    }

    /**
     * 便捷构造（显式指定后端，测试/诊断用）：固定 Langfuse 后端能力
     * （语义编码开启），与现状行为一致，既有测试无需改动。
     */
    public AgentTracer(SpanLifecycle lifecycle, AttributeSanitizer sanitizer, TraceProperties props) {
        this(lifecycle, sanitizer, props, new LangfuseBackend(), new SpanNameEncoder(sanitizer));
    }

    private AgentTracer(SpanLifecycle lifecycle, AttributeSanitizer sanitizer, TraceProperties props,
                        TraceBackend backend, SpanNameEncoder encoder) {
        this.lifecycle = lifecycle;
        this.sanitizer = sanitizer;
        this.traceEnabled = props.isTraceEnabled();
        this.backend = backend;
        this.strategy = new SpanNamingStrategy(backend, encoder);
    }

    /**
     * 会话入口：创建根 span（agent.session），返回根句柄由 Controller 持有，
     * 整棵 trace 的结束唯一收敛点（三回调/方法 finally 调用 {@code root.end()}）。
     * 按后端关联约定额外写入平台属性（如 langfuse.user.id / langfuse.session.id）。
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
            for (TraceBackend.RootAttributeMapping mapping : backend.associationAttributes()) {
                String value = resolveAssociationValue(mapping.sourceField(), conversationId, userId);
                if (value != null) {
                    root.setRaw(mapping.platformKey(), value);
                }
            }
            return root;
        } catch (Exception e) {
            log.warn("[observability] startSession 失败，降级 Noop（Fail-Open）", e);
            return NoopAgentSpan.INSTANCE;
        }
    }

    /**
     * 埋点：创建并 start 一个业务 span（父子关系自动从当前线程 scope 栈捕获）。
     * span 名是否携带语义后缀由命名策略（后端能力）决定。
     *
     * @param spec     span 类型（注册表）
     * @param semantic 业务语义输入（工具名/决策/轮次/模板键等；编码关闭时仅进属性）
     */
    public AgentSpan start(AgentSpanSpec spec, String semantic) {
        if (!traceEnabled) {
            return NoopAgentSpan.INSTANCE;
        }
        try {
            return createSpan(strategy.name(spec, semantic));
        } catch (Exception e) {
            log.warn("[observability] start 失败 span={}，降级 Noop（Fail-Open）", spec, e);
            return NoopAgentSpan.INSTANCE;
        }
    }

    /**
     * guard span 埋点：接收结构化原始语义（decision/toolName/modelName/payload），
     * 载荷编码（拼法/脱敏/限长）由 {@link SpanNameEncoder} 完成（业务类不再持有加工逻辑，
     * 观测后端解耦方案评审 13.3.2）。语义数据同时全量写入属性（AgentField.GUARD_*）。
     */
    public AgentSpan startGuard(String decision, String toolName, String modelName, String payload) {
        if (!traceEnabled) {
            return NoopAgentSpan.INSTANCE;
        }
        try {
            return createSpan(strategy.guardName(AgentSpanSpec.GUARD, decision, toolName, modelName, payload));
        } catch (Exception e) {
            log.warn("[observability] startGuard 失败，降级 Noop（Fail-Open）", e);
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
     * 便捷重载：根 span 直接 resume（AgentContext 携带 AgentSpan 时用）。
     */
    public Observation.Scope resume(AgentSpan rootSpan) {
        return rootSpan.openScope();
    }

    /** 平台关联属性取值：值复用会话参数（评审 13.2.4 MVP，落点根 span attributes） */
    private String resolveAssociationValue(AgentField sourceField, String conversationId, String userId) {
        return switch (sourceField) {
            case CONVERSATION_ID -> conversationId;
            case USER_ID -> userId;
            default -> null;
        };
    }

    /** 创建并 start observation（命名已完成清洗与限长；先 start 后 openScope 的断链契约见类注释） */
    private AgentSpan createSpan(String name) {
        Observation observation = lifecycle.create(name);
        // 断链修复（2026-08-03）：必须先 start 后 openScope！
        // openScope（onScopeOpened）会用 computeIfAbsent 把空 TracingContext 塞进 context，
        // 若先 openScope，onStart 时 getParentSpan 命中"自己已有 TracingContext"分支，
        // 返回自己的空 span → 无视父级 → 每个 span 新开 traceId。
        observation.start();
        Observation.Scope scope = lifecycle.openScope(observation);
        return new AgentSpanImpl(observation, scope, sanitizer);
    }
}