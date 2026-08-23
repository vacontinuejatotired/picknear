package com.hmdp.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentContextPropagator — TaskDecorator 传播测试。
 * <p>
 * 覆盖：提交线程捕获 → 执行线程恢复 → finally 清理（防线程复用污染）→ 嵌套任务传播。
 * </p>
 */
class AgentContextPropagatorTest {

    private final AgentContextPropagator propagator = new AgentContextPropagator();

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void should_capture_and_restore_context() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AgentContext ctx = AgentContext.builder().userId(1L).conversationId("conv-1").build();
            AgentContextHolder.set(ctx);

            AtomicReference<AgentContext> seen = new AtomicReference<>();
            Runnable task = propagator.decorate(() -> seen.set(AgentContextHolder.get()));
            CompletableFuture.runAsync(task, pool).get(5, TimeUnit.SECONDS);

            assertThat(seen.get())
                    .as("执行线程应读到提交线程捕获的同一上下文")
                    .isSameAs(ctx);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_cleanup_context_set_inside_task() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AgentContextHolder.clear();

            // 任务内部 set 的上下文必须在 finally 清理（防池化线程复用污染下一个任务）
            Runnable decorated = propagator.decorate(() ->
                    AgentContextHolder.set(AgentContext.builder().userId(99L).build()));
            CompletableFuture.runAsync(decorated, pool).get(5, TimeUnit.SECONDS);

            AtomicReference<AgentContext> nextSeen = new AtomicReference<>();
            CompletableFuture.runAsync(() -> nextSeen.set(AgentContextHolder.get()), pool)
                    .get(5, TimeUnit.SECONDS);

            assertThat(nextSeen.get())
                    .as("上一任务残留的上下文必须被清理")
                    .isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_not_affect_submitter_thread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AgentContext ctx = AgentContext.builder().userId(1L).conversationId("conv-1").build();
            AgentContextHolder.set(ctx);

            Runnable task = propagator.decorate(() -> { /* no-op */ });
            CompletableFuture.runAsync(task, pool).get(5, TimeUnit.SECONDS);

            assertThat(AgentContextHolder.get())
                    .as("提交线程的上下文不应被异步任务的清理影响")
                    .isSameAs(ctx);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_propagate_nested_tasks() throws Exception {
        // 双线程池：outer 占一个线程时 inner 仍可被另一线程执行（单线程池会自锁）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AgentContext ctx = AgentContext.builder().userId(1L).conversationId("conv-1").build();
            AgentContextHolder.set(ctx);

            // 外任务：恢复上下文后提交内任务（经同一池）→ 内任务也应读到同一上下文
            AtomicReference<AgentContext> innerSeen = new AtomicReference<>();
            Runnable inner = propagator.decorate(() -> innerSeen.set(AgentContextHolder.get()));
            Runnable outer = propagator.decorate(() -> {
                try {
                    CompletableFuture.runAsync(inner, pool).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            CompletableFuture.runAsync(outer, pool).get(5, TimeUnit.SECONDS);

            assertThat(innerSeen.get())
                    .as("嵌套任务应继承外层传播的上下文")
                    .isSameAs(ctx);
        } finally {
            pool.shutdownNow();
        }
    }
}
