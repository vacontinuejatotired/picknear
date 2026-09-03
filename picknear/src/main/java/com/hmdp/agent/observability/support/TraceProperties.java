package com.hmdp.agent.observability.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 全链路观测配置。
 * <p>
 * 配置项前缀：hmdp.ai-observability
 * </p>
 * <p>
 * 后端选型与接入参数：{@code backend.type}（观测后端）在
 * {@link com.hmdp.agent.observability.backend.BackendProperties}（前缀
 * {@code hmdp.ai-observability.backend}）；接入参数（endpoint/headers）唯一事实源是
 * {@code management.otlp.tracing.*}（观测后端解耦方案 §6.1）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai-observability")
public class TraceProperties {

    /** 业务埋点总开关（false 时 AgentTracer 整门面 Noop 空转，配额紧急开关） */
    private boolean traceEnabled = true;

    /** 观测白名单：backend 能力默认前缀 + 本清单追加（用户不可删除默认项） */
    private TraceFilter traceFilter = new TraceFilter();

    /** LLM 调用观测：请求/回复全文写入 langfuse.observation.input/output（可三态覆盖） */
    private ChatObservation chatObservation = new ChatObservation();

    /** span 命名策略的用户级覆盖（auto=跟后端能力） */
    private SpanNaming spanNaming = new SpanNaming();

    @Data
    public static class TraceFilter {
        /**
         * 额外放行的 span 前缀（默认来自 backend 能力：agent. / spring.ai. / gen_ai.，
         * 新增观测类型在此追加；追加非覆盖，评审 13.4-2）。
         */
        private List<String> includePrefixes = new ArrayList<>();
    }

    @Data
    public static class ChatObservation {
        /**
         * LLM 请求/回复全文是否写入 OTel 的 langfuse.observation.input /
         * langfuse.observation.output（Langfuse 转译后落 observation 主字段；2026-09-01
         * 由 gen_ai.request/response.content 改为 SDK 协议 key，修复主字段恒 null）。
         * 三态：{@code auto}（默认，跟随后端能力 contentSupplementRequired）/
         * {@code true}（强制开）/ {@code false}（强制关）。兼容旧配置 {@code true}/
         * {@code false} 字面量。内容经 {@link AttributeSanitizer} 脱敏（手机号/邮箱/身份证 +
         * 截断）后才写入。
         */
        private String includeContent = "auto";

        /** 解析为三态（字符串字面量兼容 auto/true/false） */
        public TriState includeContentMode() {
            return TriState.fromString(includeContent);
        }
    }

    @Data
    public static class SpanNaming {
        /**
         * 是否把语义后缀编码进 span 名。三态：{@code auto}（默认，跟随后端
         * {@code supportsSpanAttributes} 推导）/ {@code true}（强制编码）/ {@code false}（强制不编码，
         * 语义靠 AgentField 属性展示）。调试与口径迁移用。
         */
        private String semanticEncoding = "auto";

        /** 解析为三态（字符串字面量兼容 auto/true/false） */
        public TriState semanticEncodingMode() {
            return TriState.fromString(semanticEncoding);
        }
    }
}