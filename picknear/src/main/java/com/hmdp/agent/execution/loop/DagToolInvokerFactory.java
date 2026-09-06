package com.hmdp.agent.execution.loop;

import com.hmdp.agent.execution.loop.argument.ToolCallArgumentInjector;
import com.hmdp.agent.plan.executionPlan.binding.ToolParameterBinding;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构建带参数注入能力的 DAG 工具调用器。
 *
 * <p>只负责“原始 arguments + 绑定结果 → 最终 payload → ToolCallback.call”，
 * 不参与执行记录、返回类型等执行器职责。</p>
 */
@Component
public class DagToolInvokerFactory {

    private final ToolCallArgumentInjector argumentInjector;

    public DagToolInvokerFactory(ToolCallArgumentInjector argumentInjector) {
        this.argumentInjector = argumentInjector;
    }

    public ToolInvoker create(
            String toolName,
            String originalArguments,
            ToolCallback callback,
            ToolContext toolContext,
            List<ToolParameterBinding> bindings) {
        return () -> {
            String enriched = argumentInjector.inject(toolName, originalArguments, bindings);
            return callback.call(enriched, toolContext);
        };
    }
}
