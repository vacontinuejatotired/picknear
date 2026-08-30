package com.hmdp.agent;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.response.AiResponseRouter;
import com.hmdp.agent.orchestration.TaskPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 链路C：AiResponseRouter.route() 路由分发测试。
 * <p>
 * 覆盖 4 种 Decision：
 * - PLANNING → 委托 TaskPlanner
 * - BLOCK    → 推送错误事件
 * - REPLACE  → 推送替换文本
 * - PASS     → 推送原始回复
 * - 异常     → emitter.send 抛 IOException 时容错
 */
@ExtendWith(MockitoExtension.class)
class AiResponseRouterRouteTest {

    @Mock
    private TaskPlanner taskPlanner;

    @Mock
    private SseEmitter emitter;

    @InjectMocks
    private AiResponseRouter router;

    private final AgentContext ctx = AgentContext.builder().userId(1L).build();

    @Test
    void should_enter_task_planner_when_planning() {
        router.route(HookResult.planningRequired(), "统计一下", "好的",
                ctx, emitter);

        verify(taskPlanner).submit(
                eq("统计一下"), eq("好的"), eq(ctx), eq(emitter));
        verifyNoInteractions(emitter);
    }

    @Test
    void should_push_error_when_block() throws IOException {
        router.route(HookResult.block("安全拦截", "TestHook"), "输入", "回复",
                ctx, emitter);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verifyNoInteractions(taskPlanner);
    }

    @Test
    void should_push_reply_when_pass() throws IOException {
        router.route(HookResult.pass(), "你好", "你好！我是助手",
                ctx, emitter);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verifyNoInteractions(taskPlanner);
    }

    @Test
    void should_push_replaced_text_when_replace() throws IOException {
        router.route(HookResult.replace("替换后的回复", "TestHook"), "原始", "原始回复",
                ctx, emitter);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verifyNoInteractions(taskPlanner);
    }

    @Test
    void should_handle_emitter_send_exception() throws IOException {
        doThrow(new IOException("连接已关闭")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        // 不应抛异常
        router.route(HookResult.pass(), "你好", "回复", ctx, emitter);

        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
