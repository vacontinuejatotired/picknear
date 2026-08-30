package com.hmdp.agent.observability;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Langfuse 云接入冒烟测试（M1 最小验证）。
 *
 * <p>验证目标：加依赖 + 配置后，一次普通 LLM 调用能自动产生 gen_ai.* span
 * 并经 OTLP 导出到 Langfuse 云（jp.cloud.langfuse.com），网页端可见。</p>
 *
 * <p>运行前提（本机 VM 环境）：
 * <pre>
 *   set -a; source .env; set +a
 *   mvn test -Dtest=LangfuseSmokeTest
 * </pre>
 * .env 需包含 DASHSCOPE_API_KEY / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY / LANGFUSE_BASE_URL / DB_PASSWORD。
 * MySQL/Redis 地址因容器端口映射，在测试内覆盖为宿主端口。</p>
 *
 * <p>预期：Langfuse 网页 → Projects → Traces 出现一条 trace（实测转译后为
 * GENERATION "chat qwen-plus" + SPAN "http post"），带 token usage。</p>
 */
@SpringBootTest
@AutoConfigureObservability   // 必需：Boot 3.4 测试默认禁用 tracing，没有它 spanExporter 不会创建
class LangfuseSmokeTest {

    @Resource
    @Qualifier("aliibabaChatClient")
    private ChatClient chatClient;

    @Resource
    private AgentTracer agentTracer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // 容器端口映射：宿主 43307(MySQL) / 46379(Redis)
        r.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:43307/heima?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("DB_PASSWORD", "123456"));
        r.add("spring.data.redis.host", () -> "127.0.0.1");
        r.add("spring.data.redis.port", () -> "46379");
        r.add("spring.data.redis.password", () -> "281458");

        // OTLP → Langfuse 云（凭据从 .env 读取，不落代码）
        String baseUrl = System.getenv("LANGFUSE_BASE_URL");
        String pk = System.getenv("LANGFUSE_PUBLIC_KEY");
        String sk = System.getenv("LANGFUSE_SECRET_KEY");
        // 实测（2026-08-03）：jp.cloud.langfuse.com 的 OTLP 端点是完整路径
        // /api/public/otel/v1/traces（不带 /v1/traces 会 404，SDK 1.43 不自动追加）
        r.add("management.otlp.tracing.endpoint", () -> baseUrl + "/api/public/otel/v1/traces");
        r.add("management.otlp.tracing.headers.Authorization", () ->
                "Basic " + Base64.getEncoder().encodeToString((pk + ":" + sk).getBytes()));
        r.add("management.otlp.tracing.headers.x-langfuse-ingestion-version", () -> "4");
        r.add("management.tracing.sampling.probability", () -> "1.0");

        // OpenAI（MaaS compatible-mode）（test classpath 的 application.yaml 无 spring.ai 段，
        // 项目自定义 OpenAiHttpConfig 硬依赖这两个属性，这里补齐）
        r.add("spring.ai.openai.api-key", () -> System.getenv("DASHSCOPE_API_KEY"));
        r.add("spring.ai.openai.base-url", () ->
                "https://ws-mhs2k50uiwwwvefx.cn-beijing.maas.aliyuncs.com/compatible-mode");
        // model 必须走 options.model（OpenAiChatProperties 无 model 字段，chat.model 被忽略→默认 gpt-4o-mini，MaaS 不认）
        r.add("spring.ai.openai.chat.options.model", () -> "qwen-plus-2025-07-28");

        // 冒烟测试无需 chat memory 表：禁用 JDBC 仓库 schema 初始化
        // （默认 always 在上下文重建时会因 DatabaseDriver 判定顺序抛异常）
        r.add("spring.ai.chat.memory.repository.jdbc.initialize-schema", () -> "never");
    }

    @Test
    void smoke() throws InterruptedException {
        String marker = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String userContent = "只回复四个字：观测验证成功（测试标记 " + marker + "）";
        System.out.println("=====[LangfuseSmokeTest] 发起 LLM 调用 @ " + marker + " =====");

        // M2 首验：AgentTracer 创建会话根 span，LLM span 应挂到根下（同树）
        AgentSpan root = agentTracer.startSession("smoke-" + marker, "smoke-user");
        String reply;
        try {
            reply = chatClient.prompt()
                    .user(userContent)
                    .call()
                    .content();
        } finally {
            root.end();
        }

        System.out.println("=====[LangfuseSmokeTest] AI 回复: " + reply + " =====");
        System.out.println("===== 等待 BatchSpanProcessor 批量导出（~20s），随后请到 Langfuse 网页查看 =====");
        Thread.sleep(20_000);
        System.out.println("===== 导出窗口结束。若网页未见新 trace：检查摄取延迟 / OTLP 端点 / 凭据 =====");
    }
}
