package com.hmdp.agent.service.impl;

import com.hmdp.agent.hook.HookResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AiServiceImpl — processHookResult 私有方法反射测试。
 * <p>
 * 链路 A/B 端到端已在 AiServiceImplE2ETest 覆盖，此处只补充私有方法。
 * <p>
 * 已禁用：主类演进后 processHookResult 私有方法已删除，测试逻辑过时。
 */
@Disabled("processHookResult 已从 AiServiceImpl 删除，测试逻辑过时，待按主类现状重写")
@ExtendWith(MockitoExtension.class)
class AiServiceImplUnitTest {

    @Mock
    private ChatMemory chatMemory;

    private AiServiceImpl aiService;

    @BeforeEach
    void setUp() {
        aiService = new AiServiceImpl();
        ReflectionTestUtils.setField(aiService, "chatMemory", chatMemory);
    }

    /**
     * 反射调用私有方法 processHookResult
     */
    private String invokeProcessHookResult(HookResult hookResult, String content, String convId) throws Exception {
        Method method = AiServiceImpl.class.getDeclaredMethod("processHookResult",
                HookResult.class, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(aiService, hookResult, content, convId);
    }

    @Test
    void should_return_null_when_blocked() throws Exception {
        String result = invokeProcessHookResult(
                HookResult.block("安全拦截", "TestHook"), "原始", "conv-1");

        assertThat(result).as("BLOCK 应返回 null").isNull();
    }

    @Test
    void should_return_replaced_text_when_replaced() throws Exception {
        String result = invokeProcessHookResult(
                HookResult.replace("替代文本", "TestHook"), "原始", "conv-1");

        assertThat(result).as("REPLACE 应返回替代文本").isEqualTo("替代文本");
    }

    @Test
    void should_clean_history_when_replaced_with_history() throws Exception {
        HookResult replaced = HookResult.replaceWithHistory("替代文本", List.of(), "TestHook");
        invokeProcessHookResult(replaced, "原始", "conv-1");

        verify(chatMemory).clear("conv-1");
        verify(chatMemory).add(eq("conv-1"), anyList());
    }

    @Test
    void should_return_original_when_passed() throws Exception {
        String result = invokeProcessHookResult(HookResult.pass(), "原始", "conv-1");

        assertThat(result).as("PASS 应返回原文").isEqualTo("原始");
    }

    @Test
    void should_return_original_when_unknown_decision() throws Exception {
        HookResult unknown = mock(HookResult.class);
        // PLANNING 在 processHookResult 中没有对应 case，走 default 分支返回 content
        when(unknown.getDecision()).thenReturn(HookResult.Decision.PLANNING);
        String result = invokeProcessHookResult(unknown, "原始", "conv-1");

        assertThat(result).as("兜底应返回原文").isEqualTo("原始");
    }
}
