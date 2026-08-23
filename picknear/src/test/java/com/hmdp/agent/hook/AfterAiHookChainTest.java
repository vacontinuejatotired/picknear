package com.hmdp.agent.hook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AfterAiHookChain — AI 回复后 Hook 链聚合测试。
 * <p>
 * 覆盖 BLOCK > REPLACE > PLANNING > PASS 优先级规则。
 */
@ExtendWith(MockitoExtension.class)
class AfterAiHookChainTest {

    @Mock
    private AfterAiHook hookA;

    @Mock
    private AfterAiHook hookB;

    private AfterAiHookChain chain;

    @BeforeEach
    void setUp() {
        chain = new AfterAiHookChain(List.of(hookA, hookB));
    }

    @Test
    void should_return_pass_when_all_pass() {
        when(hookA.afterAi(any(), any(), any())).thenReturn(HookResult.pass());
        when(hookB.afterAi(any(), any(), any())).thenReturn(HookResult.pass());

        HookResult result = chain.execute("input", "response", null);

        assertThat(result.isPass())
                .as("全部 PASS 时应返回 PASS")
                .isTrue();
    }

    @Test
    void should_block_before_replace() {
        when(hookA.afterAi(any(), any(), any()))
                .thenReturn(HookResult.block("安全拦截", "HookA"));
        // hookB 不应被执行（BLOCK 短路）

        HookResult result = chain.execute("input", "response", null);

        assertThat(result.isBlock())
                .as("BLOCK 应优先于 REPLACE")
                .isTrue();
        verify(hookA).afterAi(any(), any(), any());
        verifyNoInteractions(hookB);
    }

    @Test
    void should_return_planning_when_any_planning() {
        when(hookA.afterAi(any(), any(), any())).thenReturn(HookResult.pass());
        when(hookB.afterAi(any(), any(), any())).thenReturn(HookResult.planningRequired());

        HookResult result = chain.execute("统计一下", "好的", null);

        assertThat(result.isPlanning())
                .as("任一 Hook 返回 PLANNING 时应聚合为 PLANNING")
                .isTrue();
    }

    @Test
    void should_skip_on_exception() {
        when(hookA.afterAi(any(), any(), any()))
                .thenThrow(new RuntimeException("意外异常"));
        when(hookB.afterAi(any(), any(), any())).thenReturn(HookResult.pass());

        HookResult result = chain.execute("input", "response", null);

        assertThat(result.isPass())
                .as("Hook 抛异常时应降级 PASS，不阻断")
                .isTrue();
        verify(hookB).afterAi(any(), any(), any());
    }
}
