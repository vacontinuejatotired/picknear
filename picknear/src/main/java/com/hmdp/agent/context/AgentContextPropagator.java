package com.hmdp.agent.context;

import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * 异步边界上下文传播器（Spring 标准 {@link TaskDecorator}）。
 * <p>
 * 装配到线程池（AgentConfig 的 {@code aiTaskExecutor} / {@code subtaskExecutor}）后，
 * 所有经线程池执行的任务自动：提交线程捕获 AgentContext → 执行线程恢复 → finally 清理。
 * 对 {@code CompletableFuture.runAsync(r, executor)} 同样生效（内部走 {@code executor.execute()}）。
 * </p>
 * <p>
 * <b>finally 清理是硬约束</b>：池化线程复用时若不清理，上一任务的上下文会污染下一个任务。
 * 捕获为 null（无上下文的任务）时 set(null) 等价于清除，语义一致。
 * </p>
 */
@Component
public class AgentContextPropagator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 提交线程捕获（可空 = 无上下文的任务）
        AgentContext captured = AgentContextHolder.get();
        return () -> {
            // 执行线程恢复
            AgentContextHolder.set(captured);
            try {
                runnable.run();
            } finally {
                // 必须清理：防线程复用污染下一任务
                AgentContextHolder.clear();
            }
        };
    }
}
