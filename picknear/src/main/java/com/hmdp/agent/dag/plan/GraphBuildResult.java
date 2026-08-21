package com.hmdp.agent.dag.plan;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 依赖图构建结果
 * 
 * <p>包含依赖图和未知工具列表，用于判断构建是否成功。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Data
@Builder
public class GraphBuildResult {
    
    /** 依赖图（工具名 → 依赖的工具列表） */
    private Map<String, List<String>> graph;
    
    /** 未注册的工具列表 */
    @Builder.Default
    private List<String> unknownTools = List.of();
    
    /**
     * 是否有效（无未知工具）
     */
    public boolean isValid() {
        return unknownTools.isEmpty();
    }
}
