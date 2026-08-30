package com.hmdp.agent.guard;

import com.hmdp.agent.guard.model.GuardResult;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.guard.model.Vote;
import com.hmdp.agent.guard.policy.ToolGuardPolicy;
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
 * ToolGuardManager — 工具守卫管理器测试。
 * <p>
 * 覆盖 BLOCK > CONFIRM > ALLOW/ABSTAIN 优先级、异常降级、空策略。
 */
@ExtendWith(MockitoExtension.class)
class ToolGuardManagerTest {

    @Mock
    private ToolGuardPolicy policyA;

    @Mock
    private ToolGuardPolicy policyB;

    private ToolGuardManager manager;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        manager = new ToolGuardManager(List.of(policyA, policyB));
        ctx = ToolInvocationContext.builder()
                .toolName("testTool").arguments("{}")
                .conversationId("conv-1").userId(1L).build();
        lenient().when(policyA.policyName()).thenReturn("PolicyA");
        lenient().when(policyB.policyName()).thenReturn("PolicyB");
    }

    @Test
    void should_allow_when_all_abstain() {
        when(policyA.vote(any())).thenReturn(Vote.ABSTAIN);
        when(policyB.vote(any())).thenReturn(Vote.ABSTAIN);

        GuardResult result = manager.evaluate(ctx);

        assertThat(result.isAllowed())
                .as("全部 ABSTAIN 应放行")
                .isTrue();
    }

    @Test
    void should_block_when_any_blocks() {
        when(policyA.vote(any())).thenReturn(Vote.ABSTAIN);
        when(policyB.vote(any())).thenReturn(Vote.BLOCK);

        GuardResult result = manager.evaluate(ctx);

        assertThat(result.isBlocked())
                .as("任一策略 BLOCK 应拦截")
                .isTrue();
    }

    @Test
    void should_confirm_when_any_confirms() {
        when(policyA.vote(any())).thenReturn(Vote.ABSTAIN);
        when(policyB.vote(any())).thenReturn(Vote.CONFIRM);

        GuardResult result = manager.evaluate(ctx);

        assertThat(result.isConfirmed())
                .as("任一策略 CONFIRM 应返回确认")
                .isTrue();
    }

    @Test
    void should_block_before_confirm() {
        when(policyA.vote(any())).thenReturn(Vote.CONFIRM);
        when(policyB.vote(any())).thenReturn(Vote.BLOCK);

        GuardResult result = manager.evaluate(ctx);

        assertThat(result.isBlocked())
                .as("BLOCK 短路，即使之前有 CONFIRM 也返回拦截")
                .isTrue();
    }

    @Test
    void should_skip_policy_on_exception() {
        when(policyA.vote(any())).thenThrow(new RuntimeException("评估异常"));
        when(policyB.vote(any())).thenReturn(Vote.ALLOW);

        GuardResult result = manager.evaluate(ctx);

        assertThat(result.isAllowed())
                .as("异常策略应跳过，其他策略正常评估")
                .isTrue();
        verify(policyB).vote(any());
    }

    @Test
    void should_allow_when_no_policies() {
        ToolGuardManager emptyManager = new ToolGuardManager(List.of());

        GuardResult result = emptyManager.evaluate(ctx);

        assertThat(result.isAllowed())
                .as("空策略列表应放行")
                .isTrue();
    }
}
