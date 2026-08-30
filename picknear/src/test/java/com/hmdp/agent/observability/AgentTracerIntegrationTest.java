package com.hmdp.agent.observability;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.service.AiService;
import com.hmdp.utils.UserHolder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2.5 集成测试：会话树父子关系断言（架构文档 §10，自研观测最硬证据）。
 * <p>
 * 验证目标（对应生产断链问题，2026-08-03）：
 * ① agent.session 根 span 存在；
 * ② prompt_hook/phase1/decision 的 parent 是 agent.session（主线程链路）；
 * ③ round 的 parent 是 agent.session（跨线程 resume 链路）；
 * ④ LLM span（spring.ai.chat.client/gen_ai.client.operation）挂在哪（诊断断链真相）。
 * </p>
 * <p>
 * OTLP endpoint 指向死地址（http://127.0.0.1:1）：span 由 InMemorySpanExporter
 * 本地捕获，导出失败自动丢弃不污染 Langfuse 配额。
 * </p>
 */
@SpringBootTest
@AutoConfigureObservability
class AgentTracerIntegrationTest {

    @Resource
    private AiService aiService;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private InMemorySpanExporter spanExporter;

    @Resource
    private io.micrometer.observation.ObservationRegistry observationRegistry;

    @Resource
    private org.springframework.context.ApplicationContext applicationContext;

    @TestConfiguration
    static class InMemorySpanConfig {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SpanProcessor inMemorySpanProcessor(InMemorySpanExporter exporter) {
            return SimpleSpanProcessor.create(exporter);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () ->
                "jdbc:mysql://127.0.0.1:43307/heima?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("DB_PASSWORD", "123456"));
        r.add("spring.data.redis.host", () -> "127.0.0.1");
        r.add("spring.data.redis.port", () -> "46379");
        r.add("spring.data.redis.password", () -> "281458");
        r.add("spring.ai.openai.api-key", () -> System.getenv("DASHSCOPE_API_KEY"));
        r.add("spring.ai.openai.base-url", () ->
                "https://ws-mhs2k50uiwwwvefx.cn-beijing.maas.aliyuncs.com/compatible-mode");
        // model 必须走 options.model（OpenAiChatProperties 无 model 字段，chat.model 被忽略→默认 gpt-4o-mini，MaaS 不认）
        r.add("spring.ai.openai.chat.options.model", () -> "qwen-plus-2025-07-28");
        r.add("spring.ai.chat.memory.repository.jdbc.initialize-schema", () -> "never");
        // 死地址：导出失败自动丢弃，不污染 Langfuse 配额（InMemory 本地捕获）
        r.add("management.otlp.tracing.endpoint", () -> "http://127.0.0.1:1/otel/v1/traces");
        r.add("management.tracing.sampling.probability", () -> "1.0");
    }

    @AfterEach
    void cleanup() {
        UserHolder.remove();
        spanExporter.reset();
    }

    @Test
    void sessionTree_shouldBeOneTrace() throws Exception {
        UserHolder.saveUserId(1010L);

        // 模拟 Controller：先 startSession 创建根 span，再构造 ObservedSseEmitter（顺序：root 必须非 null）
        AgentSpan root = agentTracer.startSession("itest-conv-1", "1010");
        // 断链修复（2026-08-04）：走包装类，验证"不手动 root.end() 会话也必然结束"
        //（complete/completeWithError/容器回调/兜底 TTL 任一路径收敛结束根 span）
        com.hmdp.agent.observability.api.ObservedSseEmitter emitter =
                new com.hmdp.agent.observability.api.ObservedSseEmitter(60_000L, root, null, 0);
        // 诊断：startSession 后当前线程 scope 栈顶（应为 agent.session）
        var cur = observationRegistry.getCurrentObservation();
        System.out.println("[诊断] startSession 后栈顶: " + (cur == null ? "null" : cur.getContext().getName()));
        // 判别实验：白名单内前缀（agent.）手动 createNotStarted，看父级机制是否生效
        // （此前 probe.manual 被白名单过滤成 Noop，实验无效）
        io.micrometer.observation.Observation probe = io.micrometer.observation.Observation
                .createNotStarted("agent.probe_test", io.micrometer.observation.Observation.Context::new, observationRegistry);
        System.out.println("[诊断] agent.probe 父级: " + (probe.getContext().getParentObservation() == null
                ? "null" : probe.getContext().getParentObservation().getContextView().getName()));
        System.out.println("[诊断] probe 线程: " + Thread.currentThread().getName()
                + " | 栈顶: " + (observationRegistry.getCurrentObservation() == null
                ? "null" : observationRegistry.getCurrentObservation().getContext().getName()));
        // 判别实验2：上下文里 ObservationRegistry bean 有几个、是否同一个
        var registries = applicationContext.getBeansOfType(io.micrometer.observation.ObservationRegistry.class);
        registries.forEach((n, r) -> System.out.println("[诊断] registry bean: " + n
                + " hash=" + System.identityHashCode(r)));
        // 判别实验3：registry 实际注册的 handlers —— getObservationHandlers() 非公共 API，
        // 需反射访问（Java 17+ classpath 下 setAccessible 可用）
        try {
            var configClass = observationRegistry.observationConfig().getClass();
            var m = configClass.getDeclaredMethod("getObservationHandlers");
            m.setAccessible(true);
            java.util.Collection<?> handlers =
                    (java.util.Collection<?>) m.invoke(observationRegistry.observationConfig());
            handlers.forEach(h -> System.out.println("[诊断] handler: " + h.getClass().getName()));
        } catch (Exception e) {
            System.out.println("[诊断] handler 链反射失败: " + e);
        }
        // 判别实验4：agent.session 的 context 里 TracingContext 是否已写入（onStart 后应有）
        var sessionObs = observationRegistry.getCurrentObservation();
        var sessionTc = (io.micrometer.tracing.handler.TracingObservationHandler.TracingContext)
                sessionObs.getContext().get(io.micrometer.tracing.handler.TracingObservationHandler.TracingContext.class);
        System.out.println("[诊断] session TracingContext: " + (sessionTc == null
                ? "null" : "span=" + sessionTc.getSpan()));
        // 判别实验5：probe 完整 start+stop，验证【OTel 层】手动路径是否真的能挂到 session 下
        // （观测层父级 ≠ OTel 层父级，必须导出 span 才能验证）
        io.micrometer.observation.Observation.Scope probeScope = probe.openScope();
        probe.start();
        System.out.println("[诊断] probe start 后 TracingContext: "
                + (((io.micrometer.tracing.handler.TracingObservationHandler.TracingContext)
                probe.getContext().get(io.micrometer.tracing.handler.TracingObservationHandler.TracingContext.class)) == null
                ? "null" : "有"));
        probe.stop();
        probeScope.close();
        aiService.chatWithToolcall("长沙天气", "itest-conv-1", emitter, root);

        // 等待完整链路（两层异步：aiTaskExecutor + subtaskExecutor）产出的 round span
        awaitSpans(spans -> {
            assertThat(spans).anyMatch(s -> s.getName().startsWith("agent.round."));
        }, Duration.ofSeconds(90));

        // 不再手动 root.end()：ObservedSseEmitter 在 complete 时自动结束根 span（R9 验证点）
        awaitSpans(spans -> {
            assertThat(spans).anyMatch(s -> s.getName().equals("agent.session"));
        }, Duration.ofSeconds(60));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        spans.forEach(s -> System.out.println("[诊断] span: " + s.getName()
                + " spanId=" + s.getSpanId().substring(0, 8)
                + " parent=" + (s.getParentSpanId().isEmpty() ? "(根)" : s.getParentSpanId().substring(0, 8))));
        Map<String, SpanData> byName = spans.stream()
                .collect(Collectors.toMap(SpanData::getName, s -> s, (a, b) -> a));

        // ④ LLM span 父级诊断（断链修复后仍可能孤儿的环节）
        spans.stream()
                .filter(s -> s.getName().startsWith("spring.ai.") || s.getName().startsWith("gen_ai."))
                .forEach(s -> System.out.println(
                        "[诊断] LLM span: " + s.getName()
                                + " parent=" + (!s.getParentSpanId().isEmpty()
                                ? byName.values().stream()
                                .filter(p -> p.getSpanId().equals(s.getParentSpanId()))
                                .map(SpanData::getName).findFirst().orElse("(未知)") : "根(无父)")));

        // probe 的 OTel 层父级对照：手动路径若正常，parent 应为 agent.session 的 spanId
        SpanData probeSpan = byName.get("agent.probe_test");
        SpanData sessionSpan = byName.get("agent.session");
        System.out.println("[诊断] probe OTel parent: " + (probeSpan == null ? "未导出(被过滤?)"
                : probeSpan.getParentSpanId().isEmpty() ? "(根/全零)"
                : probeSpan.getParentSpanId().substring(0, 8)
                + (sessionSpan != null && probeSpan.getParentSpanId().equals(sessionSpan.getSpanId())
                ? " = session ✓" : " ≠ session ✗")));

        // ① 根 span 存在
        assertThat(byName).containsKey("agent.session");

        // ② 主线程链路：prompt_hook/phase1 挂根下；decision 在 phase1 的 scope 内创建（ai-worker 线程）
        // （2026-08-03 断链修复后实测：decision 创建时栈顶=agent.phase1，观测上挂 phase1 下才是正确结构）
        assertParent(byName.get("agent.prompt_hook"), byName.get("agent.session"), "prompt_hook");
        assertParent(byName.get("agent.phase1"), byName.get("agent.session"), "phase1");
        assertParent(byName.get("agent.decision"), byName.get("agent.phase1"), "decision");

        // ③ 跨线程链路：round 挂根下（resume 生效则同 traceId）
        SpanData round = spans.stream().filter(s -> s.getName().startsWith("agent.round."))
                .findFirst().orElseThrow(() -> new AssertionError("无 agent.round span"));
        assertParent(round, byName.get("agent.session"), "round");

        // 整棵树唯一性：session 的 traceId 下所有业务 span 同 traceId
        String traceId = byName.get("agent.session").getTraceId();
        for (String name : new String[]{"agent.prompt_hook", "agent.phase1", "agent.decision"}) {
            assertThat(byName.get(name).getTraceId()).as(name + " 应同 traceId").isEqualTo(traceId);
        }
        assertThat(round.getTraceId()).as("round 应同 traceId").isEqualTo(traceId);
    }

    private void assertParent(SpanData child, SpanData parent, String childName) {
        assertThat(child).as(childName + " span 应存在").isNotNull();
        assertThat(child.getParentSpanId()).as(childName + " 的父级应为 " + parent.getName())
                .isEqualTo(parent.getSpanId());
    }

    /** 轮询等待 span 条件满足（异步链路完成） */
    private void awaitSpans(Consumer<List<SpanData>> checker, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                checker.accept(spanExporter.getFinishedSpanItems());
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw last != null ? last : new AssertionError("等待 span 超时");
    }
}
