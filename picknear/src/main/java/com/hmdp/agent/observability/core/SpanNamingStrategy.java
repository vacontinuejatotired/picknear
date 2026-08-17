package com.hmdp.agent.observability.core;

import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.model.AgentSpanSpec;

/**
 * span 命名策略（评审 13.2.2 / 13.3.2：命名决策与载荷编码的统一出口）。
 * <p>
 * 决策规则：<b>是否把语义后缀拼进 span 名</b>由观测后端能力推导——目标后端不展示自定义
 * 属性时（如 Langfuse，{@code supportsSpanAttributes=false}）拼，展示属性时（Jaeger 等）不拼
 * （语义已由 {@code AgentField} 属性全量承载）。用户级覆盖开关（auto/true/false）在配置
 * 迁移步（S5）接入，本类默认 auto 行为。
 * </p>
 * <p>
 * 两种入口：
 * <ul>
 *   <li>{@link #name(AgentSpanSpec, String)}：普通业务 span（semantic 已是字符串语义输入，
 *       如工具名/轮次号/模板键）；</li>
 *   <li>{@link #guardName(AgentSpanSpec, String, String, String, String)}：guard span 的结构化
 *       语义（decision/toolName/modelName/payload），编码与否与拼接格式在此收敛。</li>
 * </ul>
 * </p>
 */
public class SpanNamingStrategy {

    private final boolean semanticEncoding;
    private final SpanNameEncoder encoder;

    public SpanNamingStrategy(TraceBackend backend, SpanNameEncoder encoder) {
        // 单一事实源：语义编码 = 不支持属性展示（评审 13.2.2，不设并列布尔字段）
        this.semanticEncoding = backend.capabilities().defaultSemanticNameEncoding();
        this.encoder = encoder;
    }

    /** 当前命名策略是否开启语义编码（测试/诊断用） */
    public boolean semanticEncoding() {
        return semanticEncoding;
    }

    /**
     * 生成业务 span 完整名：{@code agent.{type}} 或（语义编码开启时）{@code agent.{type}.{semantic}}。
     * semantic 为 null/空白时不拼后缀。
     */
    public String name(AgentSpanSpec spec, String semantic) {
        if (!semanticEncoding || semantic == null || semantic.isBlank()) {
            return spec.spanName(null);
        }
        return spec.spanName(encoder.sanitizeName(semantic));
    }

    /**
     * 生成 guard span 完整名：语义编码开启时用 {@link SpanNameEncoder#encodeGuardSemantic}
     * 拼接 {@code decision.toolName[.modelName][.紧凑参数]}；关闭时回到无后缀 {@code agent.guard}
     * （decision/toolName/modelName/arguments 全部由属性承载，见 AgentField.GUARD_*）。
     */
    public String guardName(AgentSpanSpec spec, String decision, String toolName, String modelName, String payload) {
        if (!semanticEncoding) {
            return spec.spanName(null);
        }
        return spec.spanName(encoder.encodeGuardSemantic(decision, toolName, modelName, payload));
    }
}