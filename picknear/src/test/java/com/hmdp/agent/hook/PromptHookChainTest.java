package com.hmdp.agent.hook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PromptHookChain — 前置 Hook 链串行执行测试。
 * <p>
 * 覆盖 PASS/BLOCK/REPLACE 传递、异常降级、空链。
 */
@ExtendWith(MockitoExtension.class)
class PromptHookChainTest {

    @Mock
    private PromptHook hookA;

    @Mock
    private PromptHook hookB;

    private PromptHookChain chain;

    @BeforeEach
    void setUp() {
        chain = new PromptHookChain(List.of(hookA, hookB));
        lenient().when(hookA.hookName()).thenReturn("HookA");
        lenient().when(hookB.hookName()).thenReturn("HookB");
    }

    @Test
    void should_return_pass_when_all_hooks_pass() {
        when(hookA.beforePrompt(any(), any(), any())).thenReturn(HookResult.pass());
        when(hookB.beforePrompt(any(), any(), any())).thenReturn(HookResult.pass());

        HookResult result = chain.execute("hello", null);

        assertThat(result.isPass())
                .as("全部 PASS 时应返回 PASS")
                .isTrue();
        verify(hookA).beforePrompt(any(), any(), any());
        verify(hookB).beforePrompt(any(), any(), any());
    }

    @Test
    void should_block_immediately_when_hook_blocks() {
        when(hookA.beforePrompt(any(), any(), any())).thenReturn(HookResult.pass());
        when(hookB.beforePrompt(any(), any(), any()))
                .thenReturn(HookResult.block("检测到敏感词", "HookB"));

        HookResult result = chain.execute("bad input", null);

        assertThat(result.isBlock())
                .as("有 Hook 返回 BLOCK 时应拦截")
                .isTrue();
        assertThat(result.getReason())
                .as("拦截原因应透传")
                .contains("敏感词");
    }

    @Test
    void should_pass_modified_text_to_next_hook() {
        when(hookA.beforePrompt(any(), any(), any()))
                .thenReturn(HookResult.replace("脱敏后的文本", "HookA"));
        when(hookB.beforePrompt(any(), eq("脱敏后的文本"), any()))
                .thenReturn(HookResult.pass());

        chain.execute("原始敏感内容", null);

        verify(hookB).beforePrompt(any(), eq("脱敏后的文本"), any());
    }

    @Test
    void should_return_last_replace_when_no_block() {
        when(hookA.beforePrompt(any(), any(), any()))
                .thenReturn(HookResult.replace("第一次替换", "HookA"));
        when(hookB.beforePrompt(any(), any(), any()))
                .thenReturn(HookResult.replace("第二次替换", "HookB"));

        HookResult result = chain.execute("原始内容", null);

        assertThat(result.isReplace())
                .as("无 BLOCK 时应返回最后一个 REPLACE")
                .isTrue();
        assertThat(result.getReplacedText())
                .as("replacedText 应为最后一次替换结果")
                .isEqualTo("第二次替换");
    }

    @Test
    void should_fail_open_when_hook_throws() {
        when(hookA.beforePrompt(any(), any(), any()))
                .thenThrow(new RuntimeException("Hook 异常"));
        when(hookB.beforePrompt(any(), any(), any())).thenReturn(HookResult.pass());

        HookResult result = chain.execute("hello", null);

        assertThat(result.isPass())
                .as("Hook 抛异常时应降级为 PASS，不阻断链路")
                .isTrue();
        verify(hookB).beforePrompt(any(), any(), any());
    }

    @Test
    void should_return_pass_when_no_hooks() {
        PromptHookChain emptyChain = new PromptHookChain(List.of());

        HookResult result = emptyChain.execute("hello", null);

        assertThat(result.isPass())
                .as("空 Hook 链应返回 PASS")
                .isTrue();
    }
}
