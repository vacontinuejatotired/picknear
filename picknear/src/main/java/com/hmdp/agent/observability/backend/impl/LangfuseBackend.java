package com.hmdp.agent.observability.backend.impl;

import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.backend.TraceBackendCapabilities;
import com.hmdp.agent.observability.model.AgentField;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Langfuse 观测后端（当前默认，兼容现状行为）。
 * <p>
 * 仅承载<b>能力与关联属性映射</b>，不含任何接入参数（endpoint/鉴权头唯一事实源 =
 * {@code management.otlp.tracing.*} yaml，见观测后端解耦改造方案 §4.1）。Langfuse 的全部
 * 平台形态只允许出现在本类（prompt 仓库形态在 {@code prompt/repo/LangfusePromptRepository}）。
 * </p>
 */
@Component
public class LangfuseBackend implements TraceBackend {

    @Override
    public String id() {
        return "langfuse";
    }

    @Override
    public TraceBackendCapabilities capabilities() {
        return TraceBackendCapabilities.langfuse();
    }

    @Override
    public List<RootAttributeMapping> associationAttributes() {
        // Langfuse 控制台按 langfuse.user.id / langfuse.session.id 关联用户与会话
        return List.of(
                new RootAttributeMapping("langfuse.user.id", AgentField.USER_ID),
                new RootAttributeMapping("langfuse.session.id", AgentField.CONVERSATION_ID));
    }
}