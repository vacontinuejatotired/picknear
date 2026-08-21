package com.hmdp.agent.dag.plan;

import com.hmdp.agent.model.ToolMetadata;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 执行计划生成器
 * 
 * <p>负责：</p>
 * <ol>
 *   <li>接收 LLM 选择的工具列表</li>
 *   <li>构建依赖图</li>
 *   <li>检测循环依赖</li>
 *   <li>校验依赖完整性</li>
 *   <li>拓扑排序分层</li>
 * </ol>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Slf4j
@Component
public class PlanGenerator {
    
    @Resource
    private GraphAnalyzer graphAnalyzer;
    
    /**
     * 生成执行计划
     */
    public ExecutionPlan plan(List<String> selectedTools) {
        // 1. 构建依赖图（包含未知工具检测）
        GraphBuildResult graphResult = graphAnalyzer.buildGraph(selectedTools);
        
        // 2. 检查未知工具
        if (!graphResult.isValid()) {
            log.warn("存在未知工具: {}", graphResult.getUnknownTools());
            return ExecutionPlan.builder()
                .layers(List.of())
                .selectedTools(selectedTools)
                .valid(false)
                .invalidReason("存在未知工具: " + graphResult.getUnknownTools())
                .unknownTools(graphResult.getUnknownTools())
                .build();
        }
        
        Map<String, List<String>> graph = graphResult.getGraph();
        
        // 3. 先检测循环依赖（结构性错误优先）
        if (hasCycle(graph)) {
            log.warn("检测到循环依赖");
            return ExecutionPlan.builder()
                .layers(List.of())
                .dependencyGraph(graph)
                .selectedTools(selectedTools)
                .valid(false)
                .invalidReason("检测到循环依赖，请检查工具依赖声明")
                .build();
        }
        
        // 4. 再校验依赖完整性
        ValidationResult validation = validateDependencies(graph, selectedTools);
        if (!validation.isValid()) {
            log.warn("依赖校验失败: {}", validation.getMessage());
            return ExecutionPlan.builder()
                .layers(List.of())
                .dependencyGraph(graph)
                .selectedTools(selectedTools)
                .valid(false)
                .invalidReason(validation.getMessage())
                .build();
        }
        
        // 5. 拓扑排序分层
        List<List<String>> layers = topologicalSort(graph);
        
        log.info("生成执行计划: {} 层, 顺序: {}", layers.size(), layers);
        
        return ExecutionPlan.builder()
            .layers(layers)
            .dependencyGraph(graph)
            .selectedTools(selectedTools)
            .valid(true)
            .build();
    }
    
    /**
     * 校验依赖：选中的工具，其依赖是否也被选中
     */
    private ValidationResult validateDependencies(Map<String, List<String>> graph, List<String> selected) {
        for (String tool : selected) {
            List<String> deps = graph.get(tool);
            if (deps != null) {
                for (String dep : deps) {
                    if (!selected.contains(dep)) {
                        return ValidationResult.invalid(
                            "工具 [" + tool + "] 依赖 [" + dep + "]，但 [" + dep + "] 未被选中");
                    }
                }
            }
        }
        return ValidationResult.valid();
    }
    
    /**
     * 检测循环依赖（DFS）
     */
    private boolean hasCycle(Map<String, List<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String node : graph.keySet()) {
            if (dfsDetectCycle(node, graph, visited, recursionStack)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean dfsDetectCycle(String node, Map<String, List<String>> graph,
                                   Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(node)) return true;
        if (visited.contains(node)) return false;
        
        visited.add(node);
        recursionStack.add(node);
        
        List<String> deps = graph.get(node);
        if (deps != null) {
            for (String dep : deps) {
                if (dfsDetectCycle(dep, graph, visited, recursionStack)) {
                    return true;
                }
            }
        }
        
        recursionStack.remove(node);
        return false;
    }
    
    /**
     * 拓扑排序分层（Kahn 算法变体）
     */
    private List<List<String>> topologicalSort(Map<String, List<String>> graph) {
        List<List<String>> layers = new ArrayList<>();
        Set<String> executed = new HashSet<>();
        
        while (executed.size() < graph.size()) {
            List<String> currentLayer = new ArrayList<>();
            
            for (String tool : graph.keySet()) {
                if (executed.contains(tool)) continue;
                
                List<String> deps = graph.get(tool);
                if (deps == null || executed.containsAll(deps)) {
                    // 检查是否标记为 SequentialOnly
                    ToolMetadata meta = graphAnalyzer.getMetadata(tool);
                    if (meta != null && meta.isSequentialOnly()) {
                        // SequentialOnly 工具：检查依赖是否已满足
                        if (deps != null && !executed.containsAll(deps)) {
                            continue;  // 依赖未满足，等下一轮
                        }
                        // 先提交当前普通层
                        if (!currentLayer.isEmpty()) {
                            layers.add(currentLayer);
                            executed.addAll(currentLayer);
                            currentLayer = new ArrayList<>();
                        }
                        // SequentialOnly 工具单独一层
                        layers.add(List.of(tool));
                        executed.add(tool);
                    } else {
                        currentLayer.add(tool);
                    }
                }
            }
            
            if (!currentLayer.isEmpty()) {
                layers.add(currentLayer);
                executed.addAll(currentLayer);
            }
        }
        
        return layers;
    }
    
    /**
     * 验证结果
     */
    private static class ValidationResult {
        private final boolean valid;
        private final String message;
        
        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
        
        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
        
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }
        
        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }
}
