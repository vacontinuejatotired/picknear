package com.hmdp.agent.plan;

import com.hmdp.agent.plan.model.ParsedPlan;
import com.hmdp.agent.plan.model.ValidationOptions;
import com.hmdp.agent.plan.support.PlanValidator;
import com.hmdp.agent.plan.intent.ToolIntentTree;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.plan.model.TaskType;
import com.hmdp.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlanValidator — 校验 + SubTask 构建测试。
 */
class PlanValidatorTest {

    private PlanValidator validator;

    @BeforeEach
    void setUp() {
        // 真实意图树 + mock 注册表（工具归属与 @ToolMeta(intents=...) 一致）
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.intentsOf("queryWeather")).thenReturn(Set.of("weather"));
        when(toolRegistry.intentsOf("queryPublishedBlogs")).thenReturn(Set.of("blog"));
        when(toolRegistry.intentsOf("queryUserBlogs")).thenReturn(Set.of("blog"));
        validator = new PlanValidator(new ToolIntentTree(toolRegistry));
    }

    private ToolCallback callback(String name) {
        ToolDefinition def = ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }

    @Test
    void should_build_tasks_from_valid_entries() {
        ParsedPlan parsed = new ParsedPlan(List.of(),
                List.of(Map.of("tool", "queryWeather", "params", Map.of("city", "北京"))));

        List<SubTask> tasks = validator.validate(parsed,
                new ToolCallback[]{callback("queryWeather")}, new TaskReport(), ValidationOptions.legacy(1L));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getToolName()).isEqualTo("queryWeather");
        assertThat(tasks.get(0).getType()).isEqualTo(TaskType.TOOL_CALL);
        assertThat(tasks.get(0).getParams()).containsEntry("city", "北京");
    }

    @Test
    void should_skip_unknown_tool_and_missing_tool_field() {
        ParsedPlan parsed = new ParsedPlan(List.of(), List.of(
                Map.of("tool", "nonExistent"),
                Map.of("params", Map.of())));

        assertThat(validator.validate(parsed,
                new ToolCallback[]{callback("queryWeather")}, new TaskReport(), ValidationOptions.legacy(1L)))
                .as("未知工具与缺 tool 字段应跳过").isEmpty();
    }

    @Test
    void should_skip_completed_and_final_failed_tools() {
        TaskReport history = new TaskReport();
        history.record(List.of(SubTask.builder()
                .toolName("queryWeather").type(TaskType.TOOL_CALL)
                .status(SubTaskStatus.COMPLETED).build()));
        history.record(List.of(SubTask.builder()
                .toolName("queryBlogs").type(TaskType.TOOL_CALL)
                .status(SubTaskStatus.FAILED).retryCount(1).build()));

        ParsedPlan parsed = new ParsedPlan(List.of(), List.of(
                Map.of("tool", "queryWeather"),
                Map.of("tool", "queryBlogs")));

        assertThat(validator.validate(parsed,
                new ToolCallback[]{callback("queryWeather"), callback("queryBlogs")},
                history, ValidationOptions.legacy(1L))).isEmpty();
    }

    @Test
    void should_drop_tool_outside_declared_nodes() {
        ParsedPlan parsed = new ParsedPlan(List.of("查询→博客"),
                List.of(Map.of("tool", "queryWeather"), Map.of("tool", "queryPublishedBlogs")));

        List<SubTask> tasks = validator.validate(parsed,
                new ToolCallback[]{callback("queryWeather"), callback("queryPublishedBlogs")},
                new TaskReport(), ValidationOptions.tree(Set.of("blog"), 1L));

        assertThat(tasks).as("出树工具丢弃，其余保留").hasSize(1);
        assertThat(tasks.get(0).getToolName()).isEqualTo("queryPublishedBlogs");
    }

    @Test
    void should_fallback_to_matched_nodes_when_no_declared_intents() {
        ParsedPlan parsed = new ParsedPlan(List.of(), List.of(Map.of("tool", "queryWeather")));

        List<SubTask> tasks = validator.validate(parsed,
                new ToolCallback[]{callback("queryWeather")}, new TaskReport(),
                ValidationOptions.tree(Set.of("weather"), 1L));

        assertThat(tasks).as("无声明意图退用关键词命中节点").hasSize(1);
    }

    @Test
    void should_resolve_self_user_id_placeholder() {
        ParsedPlan parsed = new ParsedPlan(List.of(),
                List.of(Map.of("tool", "queryUserBlogs", "params", Map.of("userId", "self"))));

        List<SubTask> tasks = validator.validate(parsed,
                new ToolCallback[]{callback("queryUserBlogs")}, new TaskReport(),
                ValidationOptions.tree(Set.of("blog"), 100L));

        assertThat(tasks.get(0).getParams()).as("self 占位符应解析为真实 userId").containsEntry("userId", 100L);
    }

    @Test
    void should_not_replace_non_placeholder_user_id() {
        ParsedPlan parsed = new ParsedPlan(List.of(),
                List.of(Map.of("tool", "queryUserBlogs", "params", Map.of("userId", "张三"))));

        List<SubTask> tasks = validator.validate(parsed,
                new ToolCallback[]{callback("queryUserBlogs")}, new TaskReport(),
                ValidationOptions.tree(Set.of("blog"), 100L));

        assertThat(tasks.get(0).getParams()).as("非占位符字符串不替换").containsEntry("userId", "张三");
    }

    @Test
    void should_not_resolve_when_user_id_null() {
        ParsedPlan parsed = new ParsedPlan(List.of(),
                List.of(Map.of("tool", "queryUserBlogs", "params", Map.of("userId", "self"))));

        List<SubTask> tasks = validator.validate(parsed,
                new ToolCallback[]{callback("queryUserBlogs")}, new TaskReport(),
                ValidationOptions.legacy(null));

        assertThat(tasks.get(0).getParams()).as("匿名不替换").containsEntry("userId", "self");
    }
}
