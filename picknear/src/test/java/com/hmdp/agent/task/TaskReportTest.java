package com.hmdp.agent.task;

import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.plan.model.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskReport — 子任务执行报告测试。
 * <p>
 * 纯逻辑，直接 new，无外部依赖。
 */
class TaskReportTest {

    @Test
    void should_mark_completed() {
        TaskReport report = new TaskReport();
        report.record(List.of(createTask("queryWeather", SubTaskStatus.COMPLETED, 0)));

        assertThat(report.isCompleted("queryWeather")).as("COMPLETED 工具应标记为已完成").isTrue();
        assertThat(report.getCompleted()).as("应包含已完成任务").hasSize(1);
    }

    @Test
    void should_mark_final_failed_when_retry_ge_1() {
        TaskReport report = new TaskReport();
        report.record(List.of(createTask("deleteBlog", SubTaskStatus.FAILED, 1)));

        assertThat(report.isFinalFailed("deleteBlog")).as("retryCount>=1 应进入 finalFailed 黑名单").isTrue();
    }

    @Test
    void should_not_mark_final_failed_when_retry_0() {
        TaskReport report = new TaskReport();
        report.record(List.of(createTask("deleteBlog", SubTaskStatus.FAILED, 0)));

        assertThat(report.isFinalFailed("deleteBlog")).as("retryCount=0 不应进入 finalFailed").isFalse();
    }

    @Test
    void should_filter_final_failed_from_retry_list() {
        TaskReport report = new TaskReport();
        report.record(List.of(
                createTask("toolA", SubTaskStatus.FAILED, 1),   // finalFailed
                createTask("toolB", SubTaskStatus.FAILED, 0)    // 可重试
        ));

        List<String> retryable = report.getFailedToolNames();

        assertThat(retryable).as("应只包含可重试的工具，排除 finalFailed").containsExactly("toolB");
    }

    @Test
    void should_not_mark_completed_when_failed() {
        TaskReport report = new TaskReport();
        report.record(List.of(createTask("testTool", SubTaskStatus.FAILED, 0)));

        assertThat(report.isCompleted("testTool")).as("FAILED 工具不应标记为已完成").isFalse();
    }

    private static SubTask createTask(String toolName, SubTaskStatus status, int retryCount) {
        return SubTask.builder()
                .toolName(toolName)
                .type(TaskType.TOOL_CALL)
                .status(status)
                .retryCount(retryCount)
                .build();
    }
}
