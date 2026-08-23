package com.hmdp.agent.task;

import com.hmdp.agent.legacy.task.TaskQueue;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskQueue — 子任务队列测试（回退路径）。
 * <p>
 * 纯逻辑，无外部依赖，直接 new。
 */
class TaskQueueTest {

    @Test
    void should_return_ready_tasks_when_no_dependency() {
        SubTask taskA = SubTask.builder().id("A").status(SubTaskStatus.PENDING).build();
        TaskQueue queue = new TaskQueue(List.of(taskA));

        List<SubTask> ready = queue.getReadyTasks();

        assertThat(ready).as("无依赖的任务应直接 READY").hasSize(1);
        assertThat(ready.get(0).getId()).isEqualTo("A");
    }

    @Test
    void should_return_ready_when_dependency_completed() {
        SubTask taskA = SubTask.builder().id("A").status(SubTaskStatus.PENDING).build();
        SubTask taskB = SubTask.builder().id("B").status(SubTaskStatus.PENDING)
                .dependsOn(List.of("A")).build();
        TaskQueue queue = new TaskQueue(List.of(taskA, taskB));

        queue.markDone("A", "ok");
        List<SubTask> ready = queue.getReadyTasks();

        assertThat(ready).as("依赖完成后 B 应 READY").hasSize(1);
        assertThat(ready.get(0).getId()).isEqualTo("B");
    }

    @Test
    void should_return_ready_when_dependency_failed() {
        SubTask taskA = SubTask.builder().id("A").status(SubTaskStatus.PENDING).build();
        SubTask taskB = SubTask.builder().id("B").status(SubTaskStatus.PENDING)
                .dependsOn(List.of("A")).build();
        TaskQueue queue = new TaskQueue(List.of(taskA, taskB));

        queue.markFailed("A", "error");
        List<SubTask> ready = queue.getReadyTasks();

        assertThat(ready).as("依赖 FAILED 后 B 也应 READY（终结状态不阻塞）").hasSize(1);
        assertThat(ready.get(0).getId()).isEqualTo("B");
    }

    @Test
    void should_not_return_ready_when_dependency_pending() {
        SubTask taskA = SubTask.builder().id("A").status(SubTaskStatus.PENDING).build();
        SubTask taskB = SubTask.builder().id("B").status(SubTaskStatus.PENDING)
                .dependsOn(List.of("A")).build();
        TaskQueue queue = new TaskQueue(List.of(taskA, taskB));

        // A 无依赖所以 READY，B 依赖 A（PENDING）不应 READY
        List<SubTask> ready = queue.getReadyTasks();

        assertThat(ready.stream().map(SubTask::getId))
                .as("B 依赖 PENDING 的 A 不应就绪")
                .doesNotContain("B");
    }

    @Test
    void should_mark_done_and_completed() {
        SubTask task = SubTask.builder().id("A").status(SubTaskStatus.PENDING).build();
        TaskQueue queue = new TaskQueue(List.of(task));

        queue.markDone("A", "success");

        assertThat(task.getStatus()).as("markDone 后应为 COMPLETED").isEqualTo(SubTaskStatus.COMPLETED);
        assertThat(task.getResult()).isEqualTo("success");
    }

    @Test
    void should_mark_failed_and_increment_retry() {
        SubTask task = SubTask.builder().id("A").status(SubTaskStatus.PENDING).retryCount(0).build();
        TaskQueue queue = new TaskQueue(List.of(task));

        queue.markFailed("A", "error");

        assertThat(task.getStatus()).as("markFailed 后应为 FAILED").isEqualTo(SubTaskStatus.FAILED);
        assertThat(task.getRetryCount()).as("retryCount 应递增").isEqualTo(1);
    }

    @Test
    void should_check_all_done() {
        SubTask taskA = SubTask.builder().id("A").build();
        SubTask taskB = SubTask.builder().id("B").build();
        TaskQueue queue = new TaskQueue(List.of(taskA, taskB));

        queue.markDone("A", "ok");
        assertThat(queue.isAllDone()).as("B 未完成时应返回 false").isFalse();

        queue.markFailed("B", "err");
        assertThat(queue.isAllDone()).as("全部进入终结状态后应返回 true").isTrue();
    }
}
