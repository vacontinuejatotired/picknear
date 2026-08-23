package com.hmdp.agent.config;

import com.hmdp.agent.plan.intent.ToolIntentTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WriteGuardConsistencyCheck — 写操作审批一致性启动校验测试。
 */
class WriteGuardConsistencyCheckTest {

    private ToolIntentTree intentTree;

    @BeforeEach
    void setUp() {
        intentTree = mock(ToolIntentTree.class);
        when(intentTree.writeTools()).thenReturn(Set.of("publishTestBlog"));
    }

    @Test
    void should_pass_when_write_tools_in_confirm_list() {
        PromptGuardProperties props = new PromptGuardProperties();
        props.setConfirmTools(List.of("publishTestBlog"));

        WriteGuardConsistencyCheck check = new WriteGuardConsistencyCheck(props, intentTree);

        assertThatCode(check::validate).doesNotThrowAnyException();
    }

    @Test
    void should_fail_when_write_tool_missing_from_confirm_list() {
        PromptGuardProperties props = new PromptGuardProperties();
        props.setConfirmTools(List.of());

        WriteGuardConsistencyCheck check = new WriteGuardConsistencyCheck(props, intentTree);

        assertThatThrownBy(check::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publishTestBlog");
    }
}
