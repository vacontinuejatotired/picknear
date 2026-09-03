package com.hmdp.agent.util;

import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskType;
import com.hmdp.agent.stream.SseEventConstants;
import com.hmdp.agent.stream.SseUtils;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * SseUtils + SseEventConstants 测试。
 * <p>
 * 纯静态方法，无外部依赖。safeSend 需要 mock SseEmitter。
 */
class SseUtilsTest {

    // ═══════════════════════════════════════════════════════
    // errorEvent
    // ═══════════════════════════════════════════════════════

    @Test
    void should_build_error_event() {
        String json = SseUtils.errorEvent("出错了");

        assertThat(json).as("应包含错误消息和 code")
                .contains("\"error\":\"出错了\"")
                .contains("\"code\":5001");
    }

    @Test
    void should_handle_null_error_message() {
        String json = SseUtils.errorEvent(null);

        assertThat(json).contains("\"error\":\"\"");
    }

    // ═══════════════════════════════════════════════════════
    // escapeJson
    // ═══════════════════════════════════════════════════════

    @Test
    void should_escape_special_characters() {
        assertThat(SseUtils.escapeJson("a\"b\\c\nd"))
                .as("引号、反斜杠、换行应转义")
                .isEqualTo("a\\\"b\\\\c\\nd");
    }

    @Test
    void should_return_empty_string_for_null() {
        assertThat(SseUtils.escapeJson(null)).as("null 输入应返回空串").isEqualTo("");
    }

    // ═══════════════════════════════════════════════════════
    // metaEvent
    // ═══════════════════════════════════════════════════════

    @Test
    void should_build_meta_event() {
        String json = SseUtils.metaEvent("conv-123");

        assertThat(json).contains("\"type\":\"meta\"");
        assertThat(json).contains("\"conversationId\":\"conv-123\"");
    }

    // ═══════════════════════════════════════════════════════
    // progressEvent
    // ═══════════════════════════════════════════════════════

    @Test
    void should_build_progress_event() {
        String json = SseUtils.progressEvent("planning", "规划完成");

        assertThat(json).contains("\"stage\":\"planning\"");
        assertThat(json).contains("\"text\":\"规划完成\"");
    }

    // ═══════════════════════════════════════════════════════
    // stepEvent
    // ═══════════════════════════════════════════════════════

    @Test
    void should_build_step_event() {
        String json = SseUtils.stepEvent("queryWeather", "RUNNING");

        assertThat(json).contains("\"type\":\"progress\"");
        assertThat(json).contains("\"stage\":\"step\"");
        assertThat(json).contains("\"toolName\":\"queryWeather\"");
        assertThat(json).contains("\"status\":\"RUNNING\"");
    }

    @Test
    void should_build_step_event_with_task_id_and_description() {
        String json = SseUtils.stepEvent("t1", "queryWeather", "查天气", "COMPLETED");

        assertThat(json).contains("\"taskId\":\"t1\"");
        assertThat(json).contains("\"toolName\":\"queryWeather\"");
        assertThat(json).contains("\"description\":\"查天气\"");
        assertThat(json).contains("\"status\":\"COMPLETED\"");
    }

    // ═══════════════════════════════════════════════════════
    // planEvent
    // ═══════════════════════════════════════════════════════

    @Test
    void should_build_plan_event() {
        List<SubTask> tasks = List.of(
                SubTask.builder().id("t1").description("查天气").toolName("queryWeather")
                        .type(TaskType.TOOL_CALL).status(SubTaskStatus.PENDING).build(),
                SubTask.builder().id("t2").description("基于以上数据生成结论")
                        .type(TaskType.LLM_REASON).status(SubTaskStatus.PENDING).build());

        String json = SseUtils.planEvent(1, tasks);

        assertThat(json).contains("\"type\":\"plan\"");
        assertThat(json).contains("\"round\":1");
        assertThat(json).contains("\"id\":\"t1\"");
        assertThat(json).contains("\"description\":\"查天气\"");
        assertThat(json).contains("\"toolName\":\"queryWeather\"");
        assertThat(json).contains("\"status\":\"PENDING\"");
        assertThat(json).contains("\"type\":\"TOOL_CALL\"");
    }

    @Test
    void should_handle_null_task_id_in_plan_event() {
        List<SubTask> tasks = List.of(
                SubTask.builder().toolName("queryWeather").type(TaskType.TOOL_CALL).build());

        String json = SseUtils.planEvent(1, tasks);

        assertThat(json).as("id 为 null 时应回退为主键占位，保证前端可定位")
                .contains("\"id\":\"sub-1\"");
    }

    // ═══════════════════════════════════════════════════════
    // confirmEvent
    // ═══════════════════════════════════════════════════════

    @Test
    void should_build_confirm_event() {
        String json = SseUtils.confirmEvent("cfm_xxx", "publishTestBlog", "需要确认", "{\"title\":\"t\"}");

        assertThat(json).contains("\"type\":\"confirm\"");
        assertThat(json).contains("\"confirmId\":\"cfm_xxx\"");
        assertThat(json).contains("\"tool\":\"publishTestBlog\"");
        assertThat(json).contains("\"reason\":\"需要确认\"");
        assertThat(json).contains("\"arguments\":\"{\\\"title\\\":\\\"t\\\"}\"");
    }

    @Test
    void should_build_confirm_event_with_empty_confirm_id() {
        String json = SseUtils.confirmEvent("", "publishTestBlog", null, null);

        assertThat(json).as("confirmId 为空应序列化为空串，其余字段兜底")
                .contains("\"confirmId\":\"\"")
                .contains("\"tool\":\"publishTestBlog\"")
                .contains("\"arguments\":\"{}\"");
    }

    // ═══════════════════════════════════════════════════════
    // toJson
    // ═══════════════════════════════════════════════════════

    @Test
    void should_serialize_map_to_json() {
        String json = SseUtils.toJson(Map.of("key", "value", "num", 123));

        assertThat(json).contains("\"key\":\"value\"");
        assertThat(json).contains("\"num\":123");
    }

    @Test
    void should_serialize_empty_map() {
        String json = SseUtils.toJson(Map.of());

        assertThat(json).as("空 Map 应返回 {}").isEqualTo("{}");
    }

    // ═══════════════════════════════════════════════════════
    // safeSend
    // ═══════════════════════════════════════════════════════

    @Test
    void should_not_throw_when_send_fails() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("closed"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        // 不应抛异常
        SseUtils.safeSend(emitter, "data");
    }

    // ═══════════════════════════════════════════════════════
    // SseEventConstants
    // ═══════════════════════════════════════════════════════

    @Test
    void constants_should_have_correct_stage_values() {
        assertThat(SseEventConstants.STAGE_MERGING).as("STAGE_MERGING").isEqualTo("merging");
        assertThat(SseEventConstants.STAGE_CONFIRM).as("STAGE_CONFIRM").isEqualTo("confirm");
        assertThat(SseEventConstants.STAGE_PLANNING).as("STAGE_PLANNING").isEqualTo("planning");
        assertThat(SseEventConstants.STAGE_EXECUTING).as("STAGE_EXECUTING").isEqualTo("executing");
    }

    @Test
    void constants_should_have_correct_tool_status_values() {
        assertThat(SseEventConstants.TOOL_RUNNING).as("TOOL_RUNNING").isEqualTo("RUNNING");
        assertThat(SseEventConstants.TOOL_COMPLETED).as("TOOL_COMPLETED").isEqualTo("COMPLETED");
        assertThat(SseEventConstants.TOOL_FAILED).as("TOOL_FAILED").isEqualTo("FAILED");
    }
}
