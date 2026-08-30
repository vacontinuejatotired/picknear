package com.hmdp.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentContextHolder — ThreadLocal 载体测试。
 * <p>
 * 覆盖：未设置时 get 为 null、require 缺失抛错（Fail-Fast）、set/get/clear 生命周期。
 * </p>
 */
class AgentContextHolderTest {

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void should_return_null_when_not_set() {
        AgentContextHolder.clear();
        assertThat(AgentContextHolder.get())
                .as("未设置时应返回 null（可空读取，不抛错）")
                .isNull();
    }

    @Test
    void should_throw_when_require_missing() {
        AgentContextHolder.clear();
        assertThatThrownBy(AgentContextHolder::require)
                .as("必填读取缺失应 Fail-Fast 抛错，防静默 NPE")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentContext");
    }

    @Test
    void should_set_get_and_clear() {
        AgentContext ctx = AgentContext.builder()
                .userId(1L)
                .conversationId("conv-1")
                .originalInput("你好")
                .build();

        AgentContextHolder.set(ctx);
        assertThat(AgentContextHolder.get()).isSameAs(ctx);
        assertThat(AgentContextHolder.require()).isSameAs(ctx);

        AgentContextHolder.clear();
        assertThat(AgentContextHolder.get()).isNull();
    }

    @Test
    void should_be_isolated_between_threads() throws Exception {
        AgentContext mainCtx = AgentContext.builder().userId(1L).conversationId("main").build();
        AgentContextHolder.set(mainCtx);

        Thread worker = new Thread(() -> {
            // 其他线程的 ThreadLocal 独立：看不到主线程的上下文
            assertThat(AgentContextHolder.get()).isNull();
            AgentContext workerCtx = AgentContext.builder().userId(2L).conversationId("worker").build();
            AgentContextHolder.set(workerCtx);
            assertThat(AgentContextHolder.get()).isSameAs(workerCtx);
            AgentContextHolder.clear();
        });
        worker.start();
        worker.join();

        assertThat(AgentContextHolder.get()).isSameAs(mainCtx);
    }
}
