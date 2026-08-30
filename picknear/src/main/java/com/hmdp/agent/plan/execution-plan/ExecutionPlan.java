package com.hmdp.agent.plan.executionPlan;

import lombok.Builder;
import lombok.Data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 执行计划
 *
 * <p>描述 DAG 的分层执行计划：</p>
 * <ul>
 *   <li>layers[0]：无依赖工具，可并行执行</li>
 *   <li>layers[1]：依赖 layer 0 的工具</li>
 *   <li>...以此类推</li>
 * </ul>
 */
@Data
@Builder
public class ExecutionPlan {

    /** 分层结果：layers[0] = 无依赖工具，layers[1] = 依赖 layer 0 的工具... */
    private List<List<String>> layers;

    /** 依赖图 */
    private Map<String, List<String>> dependencyGraph;

    /** LLM 选择的工具 */
    private List<String> selectedTools;

    /** 校验是否通过 */
    private boolean valid;

    /** 校验失败原因 */
    private String invalidReason;

    /** 未知工具列表 */
    @Builder.Default
    private List<String> unknownTools = List.of();

    /**
     * 获取执行顺序（展平）
     */
    public List<String> getExecutionOrder() {
        if (layers == null) return List.of();
        return layers.stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }
}
