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
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai-observability")
public class TraceProperties {

    /** 业务埋点总开关（false 时 AgentTracer 空转，M2 使用） */
    private boolean traceEnabled = true;

    /** 观测白名单：只放行指定前缀的 span，其余丢弃（省 Langfuse 配额） */
    private TraceFilter traceFilter = new TraceFilter();

    /** LLM 调用观测：是否把请求/回复全文写入 gen_ai.request/response.content（Langfuse input/output） */
    private ChatObservation chatObservation = new ChatObservation();

    @Data
    public static class TraceFilter {
        /**
         * 额外放行的 span 前缀（默认内置 agent. / spring.ai. / gen_ai.，
         * 新增观测类型在此追加）。
         */
        private List<String> includePrefixes = new ArrayList<>();
    }

    @Data
    public static class ChatObservation {
        /**
         * 把 LLM 请求/回复全文写入 OTel 的 gen_ai.request.content / gen_ai.response.content。
         * 默认 true（用户要求 Langfuse 显示详细输入输出）；false 恢复"不记 prompt 全文"（架构文档 D10）。
         * 内容经 {@link AttributeSanitizer} 脱敏（手机号/邮箱/身份证 + 截断）后才写入。
         */
        private boolean includeContent = true;
    }
}
