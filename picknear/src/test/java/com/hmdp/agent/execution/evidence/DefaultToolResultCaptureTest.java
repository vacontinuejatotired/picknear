package com.hmdp.agent.execution.evidence;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.execution.model.ToolEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultToolResultCapture — 轮级证据累加器单测。
 * <p>
 * 覆盖：同线程登记与顺序快照、snapshot 后清空（防跨轮残留）、无 AgentContext 时 fail-open、
 * 经 AgentContextPropagator 传播的并行工具线程也能写入。
 * </p>
 */
class DefaultToolResultCaptureTest {

    private final DefaultToolResultCapture capture = new DefaultToolResultCapture();

    @BeforeEach
    void setUp() {
        AgentContext ctx = AgentContext.builder()
                .userId(1L).conversationId("conv-1").build();
        AgentContextHolder.set(ctx);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void should_collect_in_order_and_clear_after_snapshot() {
        capture.begin();
        capture.capture("queryTotalShops", "当前共有 120 家店");
        capture.capture("queryWeather", "长沙晴 25°C");

        List<ToolEvidence> evidence = capture.snapshot();

        assertThat(evidence)
                .extracting(ToolEvidence::toolName)
                .containsExactly("queryTotalShops", "queryWeather");
        assertThat(evidence.get(0).raw()).isEqualTo("当前共有 120 家店");
        assertThat(evidence.get(0).refId()).isNull();

        // snapshot 后上下文中的累加器已移除 → 再 snapshot 为空（防跨轮残留）
        assertThat(capture.snapshot()).isEmpty();
    }

    @Test
    void should_ignore_blank_and_null_capture() {
        capture.begin();
        capture.capture("q", "");
        capture.capture(null, "x");
        capture.capture("q2", null);

        assertThat(capture.snapshot()).isEmpty();
    }

    @Test
    void should_fail_open_without_context() {
        AgentContextHolder.clear();
        capture.capture("q", "值");

        assertThat(capture.snapshot()).isEmpty();
    }

    @Test
    void should_collect_from_parallel_threads_via_propagator() throws Exception {
        AgentContextPropagatorDecorator decorator = new AgentContextPropagatorDecorator();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            capture.begin();
            CompletableFuture<?>[] futures = new CompletableFuture<?>[]{
                    CompletableFuture.runAsync(
                            decorator.decorate(() -> capture.capture("a", "1")), pool),
                    CompletableFuture.runAsync(
                            decorator.decorate(() -> capture.capture("b", "2")), pool)
            };
            CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

            List<ToolEvidence> evidence = capture.snapshot();
            assertThat(evidence).hasSize(2);
            assertThat(evidence)
                    .extracting(ToolEvidence::toolName)
                    .containsExactlyInAnyOrder("a", "b");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 模拟 AgentContextPropagator：子线程捕获主线程上下文（生产由 TaskDecorator 完成） */
    private static final class AgentContextPropagatorDecorator {
        Runnable decorate(Runnable task) {
            AgentContext captured = AgentContextHolder.get();
            return () -> {
                AgentContextHolder.set(captured);
                try {
                    task.run();
                } finally {
                    AgentContextHolder.clear();
                }
            };
        }
    }
}
