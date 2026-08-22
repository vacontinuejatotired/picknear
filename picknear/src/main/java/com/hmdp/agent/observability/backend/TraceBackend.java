package com.hmdp.agent.observability.backend;

import com.hmdp.agent.observability.model.AgentField;

import java.util.List;

/**
 * 观测后端策略接口（策略模式）—— 行为适配层的决策依据。
 * <p>
 * 设计约束（观测后端解耦改造方案 §4.1，评审 13.2.1 收敛）：
 * <ul>
 *   <li><b>不承载接入参数</b>：endpoint/headers/鉴权唯一事实源 =
 *       {@code management.otlp.tracing.*} yaml / 环境变量，本接口不含任何 URL/凭据。</li>
 *   <li>各实现以 {@code @Component} 注册，装配器按 {@link #id()} 索引（Spring Map/List 收集），
 *       新增后端只加实现类 + {@link TraceBackendType} 枚举项 + 配置段（OCP）。</li>
 * </ul>
 * </p>
 */
public interface TraceBackend {

    /** 平台标识：与 {@link TraceBackendType#id()} 一一对应（langfuse/jaeger/signoz/collector/console/noop） */
    String id();

    /** 平台能力描述 —— 命名/补发/白名单/关联等行为适配的依据 */
    TraceBackendCapabilities capabilities();

    /**
     * 根 span 会话/用户关联属性映射（评审 13.2.4 静态映射 MVP）。
     * <p>
     * 每个映射表达「平台属性名 ← 复用哪个 {@link AgentField} 的值」；值由 AgentTracer
     * startSession 时从会话参数（conversationId/userId）提取，落点默认根 span attributes。
     * 后续后端差异扩展为「关联属性描述符」（属性名 + 值提取 + 落点），本方法签名为其雏形。
     * </p>
     * 默认空实现：多数后端无平台特有关联约定。
     */
    default List<RootAttributeMapping> associationAttributes() {
        return List.of();
    }

    /**
     * 根 span 关联属性映射条目：platformKey = 目标后端识别的属性名（如 langfuse.user.id），
     * sourceField = 复用的现有字段（值来自会话参数，见 {@link AgentField#CONVERSATION_ID} /
     * {@link AgentField#USER_ID}）。
     */
    record RootAttributeMapping(String platformKey, AgentField sourceField) {
    }
}