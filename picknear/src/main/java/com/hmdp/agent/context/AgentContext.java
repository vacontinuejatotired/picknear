package com.hmdp.agent.context;

import com.hmdp.agent.observability.api.AgentSpan;
import org.springframework.ai.chat.messages.Message;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 会话请求上下文（请求级：一个请求一条链路）。
 * <p>
 * 统一承载 AI 链路中散落的 userId / conversationId / originalInput / history / rootSpan，
 * 替代 UserHolder（异步丢失）、ChatContext（已并入）、ToolContext（依赖手动塞）、
 * TaskSnapshot（跨请求重建）之间的手递。请求入口创建一次放入 {@link AgentContextHolder}：
 * 同步段直接读取，异步边界由 {@link AgentContextPropagator}（TaskDecorator）自动捕获/恢复。
 * </p>
 * <p>
 * 边界定义（写入代码，防误用）：
 * <ul>
 *   <li>{@code AgentContext} = <b>请求级</b>上下文（一个请求一条链路）</li>
 *   <li>{@code TaskSnapshot} / {@code AgentApproval} = <b>跨请求持久化</b>上下文（CONFIRM 恢复用，必须显式落库）</li>
 *   <li>{@code SubTaskExecution} / {@code SubTaskPlan} = <b>任务级</b>上下文（子 Agent 要执行什么）</li>
 *   <li>{@code UserHolder} = <b>Web 请求</b>认证上下文（不进异步）</li>
 * </ul>
 * </p>
 */
public final class AgentContext {

    /** 当前登录用户 ID（可空：非登录态场景） */
    private final Long userId;

    /** 会话 ID（多轮对话标识） */
    private final String conversationId;

    /** 原始用户输入（历史落库 / 快照恢复用，Hook 替换前的原文） */
    private final String originalInput;

    /** 会话历史（Hook 链用，可空；只存引用不拷贝，避免大历史每请求复制） */
    private final List<Message> history;

    /** 观测根 span（跨线程挂载用，可空；JSON 模式无观测） */
    private final AgentSpan rootSpan;

    /** 扩展点：阶段标记等临时信息（线程安全；不再为每个新字段新增载体） */
    private final Map<String, Object> attributes;

    private AgentContext(Builder builder) {
        this.userId = builder.userId;
        this.conversationId = builder.conversationId;
        this.originalInput = builder.originalInput;
        this.history = builder.history != null ? builder.history : Collections.emptyList();
        this.rootSpan = builder.rootSpan;
        this.attributes = builder.attributes != null ? builder.attributes : new ConcurrentHashMap<>();
    }

    // ---- 只读 getter（主字段不可变；扩展信息走 attributes） ----

    public Long userId() {
        return userId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String originalInput() {
        return originalInput;
    }

    public List<Message> history() {
        return history;
    }

    public AgentSpan rootSpan() {
        return rootSpan;
    }

    // ---- attributes 便捷读写 ----

    public Object attribute(String key) {
        return attributes.get(key);
    }

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    /**
     * 派生新上下文：替换原始输入（快照恢复时重建上下文用），其余字段（含 attributes 引用）不变。
     */
    public AgentContext withOriginalInput(String newOriginalInput) {
        return builder()
                .userId(userId)
                .conversationId(conversationId)
                .originalInput(newOriginalInput)
                .history(history)
                .rootSpan(rootSpan)
                .attributes(attributes)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long userId;
        private String conversationId;
        private String originalInput;
        private List<Message> history;
        private AgentSpan rootSpan;
        private Map<String, Object> attributes;

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder originalInput(String originalInput) {
            this.originalInput = originalInput;
            return this;
        }

        public Builder history(List<Message> history) {
            this.history = history;
            return this;
        }

        public Builder rootSpan(AgentSpan rootSpan) {
            this.rootSpan = rootSpan;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public AgentContext build() {
            return new AgentContext(this);
        }
    }
}
