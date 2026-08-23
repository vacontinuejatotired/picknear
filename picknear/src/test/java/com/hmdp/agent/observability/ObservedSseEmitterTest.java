package com.hmdp.agent.observability;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.ObservedSseEmitter;
import com.hmdp.agent.observability.model.AgentField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * ObservedSseEmitter 生命周期单测（设计 §6 测试计划）。
 * <p>
 * 验证点：complete→root 已 end（且 finish 属性先于 end 写入）、双 complete only-once、
 * complete‖error 并发 first-wins、guard 兜底触发、complete 后 guard 取消、root=null 降级、
 * guardDelayMs 校验（M-1）。
 * </p>
 * <p>
 * SseEmitter 在无 servlet 容器下构造/complete 是安全的（state CAS + handler==null no-op），
 * 故可用纯单测覆盖生命周期语义。
 * </p>
 */
class ObservedSseEmitterTest {

    private AgentSpan root;
    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        root = mock(AgentSpan.class);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void complete_shouldEndRootSpan_withFinishAttributeBeforeEnd() {
        ObservedSseEmitter emitter = emitter(60_000L, root, null, 0);

        emitter.complete();

        var inOrder = inOrder(root);
        inOrder.verify(root).set(AgentField.FINISH, "COMPLETE");
        inOrder.verify(root).end();
        verifyNoMoreInteractions(root);
    }

    @Test
    void doubleComplete_shouldEndRootSpanOnlyOnce() {
        ObservedSseEmitter emitter = emitter(60_000L, root, null, 0);

        emitter.complete();
        emitter.complete();

        verify(root, times(1)).end();
        verify(root, times(1)).set(AgentField.FINISH, "COMPLETE");
    }

    @Test
    void completeAndErrorConcurrent_shouldEndRootSpanOnlyOnce_firstReasonWins() throws Exception {
        ObservedSseEmitter emitter = emitter(60_000L, root, null, 0);
        int rounds = 200;
        CyclicBarrier barrier = new CyclicBarrier(2);

        for (int i = 0; i < rounds; i++) {
            Thread t1 = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception ignored) {
                }
                emitter.complete();
            });
            Thread t2 = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception ignored) {
                }
                emitter.completeWithError(new RuntimeException("boom"));
            });
            t1.start();
            t2.start();
            t1.join();
            t2.join();
        }

        verify(root, times(1)).end();
        verify(root, times(1)).set(eq(AgentField.FINISH), any());
    }

    @Test
    void guard_shouldFireTimeoutReason_whenNeverCompleted() throws Exception {
        // M-1：guard 须 > timeoutMs（仅 scheduler 非 null 时校验）
        ObservedSseEmitter emitter = emitter(50L, root, scheduler, 200L);
        CountDownLatch ended = new CountDownLatch(1);
        doAnswer(inv -> {
            ended.countDown();
            return null;
        }).when(root).end();

        assertThat(ended.await(5, TimeUnit.SECONDS)).as("guard 应在延迟后触发 end()").isTrue();

        verify(root, times(1)).set(AgentField.FINISH, "TIMEOUT");
        verify(root, times(1)).end();
    }

    @Test
    void completeBeforeGuard_shouldKeepReasonAndEndOnlyOnce_evenIfGuardRunsLate() throws Exception {
        ObservedSseEmitter emitter = emitter(50L, root, scheduler, 500L);
        java.util.concurrent.atomic.AtomicInteger endCount = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            endCount.incrementAndGet();
            return null;
        }).when(root).end();

        emitter.complete();   // 正常完成 → finish 内 cancel 兜底任务

        // 等待超过 guard 延迟：若 cancel 失效/guard 仍执行，CAS 保证 reason 不被覆盖、end 不二次执行
        Thread.sleep(800);

        verify(root, times(1)).end();
        verify(root, times(1)).set(AgentField.FINISH, "COMPLETE");
        assertThat(endCount.get()).isEqualTo(1);
    }

    @Test
    void rootNull_shouldDegrade_neverThrow() {
        ObservedSseEmitter emitter = emitter(60_000L, null, null, 0);

        assertThatCode(emitter::complete).doesNotThrowAnyException();
        assertThatCode(() -> emitter.completeWithError(new RuntimeException("boom"))).doesNotThrowAnyException();
    }

    @Test
    void guardDelayLeTimeout_shouldFailFast_whenSchedulerPresent() {
        assertThatThrownBy(() -> emitter(60_000L, root, scheduler, 60_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guardDelayMs");
        assertThatThrownBy(() -> emitter(60_000L, root, scheduler, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guardDelayLeTimeout_shouldBeIgnored_whenSchedulerNull() {
        // scheduler=null 时 guardDelayMs 是死参数（无 guard 可触发），不校验（M-1 语义）
        assertThatCode(() -> emitter(60_000L, root, null, 0)).doesNotThrowAnyException();
    }

    private ObservedSseEmitter emitter(long timeoutMs, AgentSpan root,
                                       ThreadPoolTaskScheduler s, long guardDelayMs) {
        return new ObservedSseEmitter(timeoutMs, root, s, guardDelayMs);
    }
}
