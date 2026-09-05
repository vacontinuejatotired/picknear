package com.hmdp.agent.history.compression;

import com.hmdp.agent.context.AgentContextHolder;
import org.springframework.core.task.TaskDecorator;

/**
 * 压缩池专用 TaskDecorator：只清理、不传播（显式任务参数携带会话身份，禁用 AgentContextPropagator，
 * 杜绝从投递请求线程捕获到的上下文污染池线程复用）。
 */
public class CleanupTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return () -> {
            try {
                AgentContextHolder.clear();
                runnable.run();
            } finally {
                AgentContextHolder.clear();
            }
        };
    }
}