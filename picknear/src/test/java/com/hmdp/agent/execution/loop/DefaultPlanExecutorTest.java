package com.hmdp.agent.execution.loop;

import com.hmdp.agent.config.properties.DagProperties;
import com.hmdp.agent.execution.strategy.NoRetryStrategy;
import com.hmdp.agent.execution.strategy.NoTimeoutStrategy;
import com.hmdp.agent.execution.strategy.RetryStrategy;
import com.hmdp.agent.execution.strategy.TimeoutStrategy;
import com.hmdp.agent.plan.executionPlan.ExecutionPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DefaultPlanExecutor 行为测试：同层并行、跨层串行、失败隔离、超时、重试。
 */
class DefaultPlanExecutorTest {

    private InMemoryToolResultStore store;
    private DagProperties dagProperties;
    private RetryStrategy retryStrategy;
    private TimeoutStrategy timeoutStrategy;
    private ThreadPoolExecutor executor;
    private DefaultPlanExecutor dagExecutor;

    @BeforeEach
    void setUp() {
        store = new InMemoryToolResultStore();
        dagProperties = new DagProperties();
        dagProperties.setLayerTimeoutSeconds(30);
        dagProperties.setToolTimeoutSeconds(5);
        retryStrategy = new NoRetryStrategy();
        timeoutStrategy = new NoTimeoutStrategy();
        executor = daemonPool(2);

        dagExecutor = new DefaultPlanExecutor();
        ReflectionTestUtils.setField(dagExecutor, "toolResultStore", store);
        ReflectionTestUtils.setField(dagExecutor, "executor", executor);
        ReflectionTestUtils.setField(dagExecutor, "dagProperties", dagProperties);
        ReflectionTestUtils.setField(dagExecutor, "retryStrategy", retryStrategy);
        ReflectionTestUtils.setField(dagExecutor, "timeoutStrategy", timeoutStrategy);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void execute_sameLayerTools_shouldRunInParallelAndReturnBoth() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();

        Map<String, ToolInvoker> tools = Map.of(
                "t1", countingTool("t1", bothStarted, active, peakActive),
                "t2", countingTool("t2", bothStarted, active, peakActive));

        DagExecutionResult result = dagExecutor.execute(plan("t1", "t2"), tools);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResults()).containsEntry("t1", "t1").containsEntry("t2", "t2");
        assertThat(result.getExecutedTools()).containsExactlyInAnyOrder("t1", "t2");
        assertThat(peakActive.get()).as("同层无依赖工具应真正并发").isEqualTo(2);
    }

    @Test
    void execute_downstreamLayer_shouldWaitForUpstreamLayer() throws Exception {
        List<String> order = new ArrayList<>();
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        CountDownLatch upstreamStarted = new CountDownLatch(1);

        Map<String, ToolInvoker> tools = Map.of(
                "slow", () -> {
                    synchronized (order) {
                        order.add("slow-start");
                    }
                    upstreamStarted.countDown();
                    if (!releaseUpstream.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("上游未按时释放");
                    }
                    synchronized (order) {
                        order.add("slow-end");
                    }
                    return "slow-val";
                },
                "after", () -> {
                    synchronized (order) {
                        order.add("after");
                    }
                    return "after-val";
                });

        Thread executorThread = new Thread(() ->
                dagExecutor.execute(plan(List.of(List.of("slow"), List.of("after"))), tools));
        executorThread.start();

        assertThat(upstreamStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        assertThat(order).as("下游层不应在上游完成前启动").doesNotContain("after");

        releaseUpstream.countDown();
        executorThread.join(3000);
        assertThat(executorThread.isAlive()).isFalse();
        assertThat(order).containsExactly("slow-start", "slow-end", "after");
    }

    @Test
    void execute_upstreamFailure_shouldStoreNullAndStillRunDownstreamLayer() {
        Map<String, ToolInvoker> tools = Map.of(
                "fail", () -> {
                    throw new RuntimeException("boom");
                },
                "after", () -> {
                    assertThat(store.getRawResult("fail")).isNull();
                    return "after-val";
                });

        DagExecutionResult result = dagExecutor.execute(
                plan(List.of(List.of("fail"), List.of("after"))), tools);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailedTools()).containsExactly("fail");
        assertThat(result.getResults()).containsEntry("after", "after-val");
        assertThat(store.getRawResult("fail")).isNull();
    }

    @Test
    void execute_oneToolFails_shouldNotBlockSameLayerSiblings() {
        Map<String, ToolInvoker> tools = Map.of(
                "fail", () -> {
                    throw new RuntimeException("boom");
                },
                "ok", () -> "ok-val");

        DagExecutionResult result = dagExecutor.execute(plan("fail", "ok"), tools);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailedTools()).containsExactly("fail");
        assertThat(result.getFailedReasons()).containsKey("fail");
        assertThat(result.getResults()).containsEntry("ok", "ok-val");
        assertThat(result.getExecutedTools()).as("失败工具不应占用成功执行列表").containsExactly("ok");
    }

    @Test
    void execute_layerExceedsTimeout_shouldReturnTimeoutError() {
        dagProperties.setLayerTimeoutSeconds(1);
        CountDownLatch neverRelease = new CountDownLatch(1);
        Map<String, ToolInvoker> tools = Map.of(
                "blocked", () -> {
                    neverRelease.await(10, TimeUnit.SECONDS);
                    return "too-late";
                });

        DagExecutionResult result = dagExecutor.execute(plan("blocked"), tools);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("超时");
        neverRelease.countDown();
    }

    @Test
    void execute_transientFailure_shouldRetryUntilSuccess() throws Exception {
        RetryStrategy retry = mock(RetryStrategy.class);
        when(retry.shouldRetry(any(), anyInt())).thenAnswer(inv ->
                inv.<Integer>getArgument(1) < 2);
        when(retry.getRetryDelay(anyInt())).thenReturn(0L);
        ReflectionTestUtils.setField(dagExecutor, "retryStrategy", retry);
        dagProperties.getRetry().setMaxRetries(2);

        AtomicInteger attempts = new AtomicInteger();
        Map<String, ToolInvoker> tools = Map.of(
                "flaky", () -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new RuntimeException("transient");
                    }
                    return "flaky-ok";
                });

        DagExecutionResult result = dagExecutor.execute(plan("flaky"), tools);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResults()).containsEntry("flaky", "flaky-ok");
        assertThat(attempts.get()).as("前两次失败应触发重试").isEqualTo(3);
    }

    @Test
    void benchmark_sameLayerIndependentTools_shouldBeatSequentialBaseline() throws Exception {
        int toolCount = 6;
        long perToolDelayMs = 100;
        AtomicInteger marker = new AtomicInteger();
        Map<String, ToolInvoker> tools = new java.util.LinkedHashMap<>();
        for (int i = 0; i < toolCount; i++) {
            String toolName = "t" + i;
            tools.put(toolName, () -> {
                marker.incrementAndGet();
                Thread.sleep(perToolDelayMs);
                return toolName;
            });
        }

        long serialStart = System.nanoTime();
        for (ToolInvoker invoker : tools.values()) {
            invoker.invoke();
        }
        long serialDurationMs = millisSince(serialStart);

        marker.set(0);
        long dagStart = System.nanoTime();
        DagExecutionResult result = dagExecutor.execute(plan(tools.keySet().stream().toList()), tools);
        long dagDurationMs = millisSince(dagStart);

        System.out.printf("DAG 基准: %d 个无依赖工具(各 %dms): 串行 %dms, DAG(2线程) %dms%n",
                toolCount, perToolDelayMs, serialDurationMs, dagDurationMs);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResults()).hasSize(toolCount);
        assertThat(marker.get()).isEqualTo(toolCount);
        assertThat(dagDurationMs).as("同层并发应显著快于串行")
                .isLessThan(serialDurationMs * 7 / 10);
    }

    private ToolInvoker countingTool(String value, CountDownLatch bothStarted,
            AtomicInteger active, AtomicInteger peakActive) {
        return () -> {
            int now = active.incrementAndGet();
            peakActive.accumulateAndGet(now, Math::max);
            bothStarted.countDown();
            if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("同层工具未同时就绪");
            }
            Thread.sleep(50);
            active.decrementAndGet();
            return value;
        };
    }

    private ExecutionPlan plan(String... tools) {
        return plan(List.of(List.of(tools)));
    }

    private ExecutionPlan plan(java.util.Collection<String> tools) {
        return plan(List.of(List.copyOf(tools)));
    }

    private ExecutionPlan plan(List<List<String>> layers) {
        List<String> flattened = layers.stream().flatMap(List::stream).toList();
        return ExecutionPlan.builder()
                .layers(layers)
                .selectedTools(flattened)
                .valid(true)
                .build();
    }

    private ThreadPoolExecutor daemonPool(int size) {
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "dag-executor-test");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
