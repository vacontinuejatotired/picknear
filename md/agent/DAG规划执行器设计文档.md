# DAG 规划执行器设计文档

> 版本：v1.9（最终版）
> 更新日期：2026-08-04
> 状态：设计阶段

---

## 版本历史

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| v1.0 | 2026-08-04 | 初始版本 |
| v1.1 | 2026-08-04 | 第一轮审查修复：ThreadLocal→共享字段、失败返回null、添加超时/重试/指标等 |
| v1.2 | 2026-08-04 | 第二轮审查修复：synchronized锁、ToolResultEntry包裹、GraphBuildResult、SequentialOnly bug、重试Thread.sleep阻塞、MetricsCollector单例累积、isSuccess空值判断等 |
| v1.3 | 2026-08-04 | 第三轮审查修复：删除废弃MetricsCollector引用、failedReasons默认值、getByType搜索当前层、SequentialOnly依赖检查、Stats添加Logger、@Tool name属性 |
| v1.4 | 2026-08-04 | 第四轮审查修复：compressLength从配置读取、compressor注入、store带类型信息、按工具名配置超时、ParameterInfo添加dependencyReturnType、isRetryable支持简写类名 |
| v1.5 | 2026-08-04 | 第五轮审查修复：DagProperties补充toolTimeouts配置类、application.yml补充tool-timeouts示例、失败时store类型信息、性能保障表格更新、文件清单补充SubTaskProperties和ToolResultCompressor |
| v1.6 | 2026-08-04 | 第六轮审查修复：依赖改为接口（ToolResultStore/PlanExecutor）、抽取RetryStrategy/TimeoutStrategy/ToolResultCompressor策略接口、DefaultPlanExecutor实现、文件清单补充策略接口和实现 |
| v1.7 | 2026-08-04 | 包结构和命名重构：DependencyTool→ToolResultStore、DependencyToolImpl→InMemoryToolResultStore、LocalPlanExecutor→DefaultPlanExecutor、ResultCompressor→ToolResultCompressor、统一包结构 |
| v1.8 | 2026-08-04 | dag包内部结构重构：DependencyGraphBuilder→GraphAnalyzer、DagPlanner→PlanGenerator、DagExecutor→PlanExecutor、DefaultDagExecutor→DefaultPlanExecutor、合并store到executor、扁平化strategy和review |
| v1.9 | 2026-08-04 | 最终版：架构图组件名称同步、DagProperties补充策略配置字段、后续优化表格补充 |

---

## 一、概述

### 1.1 背景

当前 Agent 模块的工具调用有两种模式：

| 模式 | 实现类 | 特点 |
|------|--------|------|
| 串行 | `SerialToolLoop` | 每次只调一个工具，安全但慢 |
| 批量并行 | `BatchToolLoop` | 所有工具并行，快但不考虑依赖 |

**问题**：无法同时满足"无依赖并行"和"有依赖串行"的需求。

### 1.2 目标

实现混合执行策略：
- **无依赖的工具**：并行执行（提高效率）
- **有依赖的工具**：串行执行（保证正确性）

### 1.3 设计原则

1. **依赖关系静态定义**：由开发者在代码中通过 `@DependsOn` 注解声明，保证可靠性
2. **LLM 只负责选工具**：不声明依赖关系，降低不可控风险
3. **框架保证执行正确性**：静态校验 + 拓扑排序 + 分层执行
4. **降级兜底**：LLM 规划错误时自动降级到串行执行
5. **超时保护**：层级超时 + 单工具超时，防止无限阻塞
6. **类型安全**：失败工具保持类型一致性，防止 ClassCastException

---

## 二、架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        整体架构                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐    ┌─────────────────┐    ┌────────────┐  │
│  │  LLM 选择   │───▶│ PlanGenerator   │───▶│  Executor  │  │
│  │  工具+参数   │    │ (拓扑排序分层)   │    │ (分层执行) │  │
│  └─────────────┘    └────────┬────────┘    └────────────┘  │
│         │                    │                    │          │
│         │              ┌─────┴─────┐              │          │
│         │              │GraphAnalyzer│              │          │
│         │              │(依赖图分析) │              │          │
│         │              └─────┬─────┘              │          │
│         │                    │                    │          │
│         ▼                    ▼                    ▼          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              ToolResultStore (结果存储)               │   │
│  │   - 存储工具执行结果                                  │   │
│  │   - 提供 get(index) / getByType() 获取依赖          │   │
│  │   - 失败工具返回 null，保持类型一致                   │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              PlanValidator (规划校验)                │   │
│  │   - 先检测循环依赖（结构性错误优先）                  │   │
│  │   - 再校验依赖完整性                                  │   │
│  │   - 可选：审查 LLM                                   │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Metrics (指标收集，每次执行独立)         │   │
│  │   - 记录每个工具执行时间                              │   │
│  │   - 统计成功率、平均耗时                              │   │
│  │   - 随 DagExecutionResult 返回，不共享状态           │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 职责划分

| 组件 | 职责 | 可控性 |
|------|------|--------|
| **LLM** | 选择工具、提供参数 | 不可控（但只做选择） |
| **GraphAnalyzer** | 依赖图分析、拓扑排序 | 100% 可控 |
| **PlanGenerator** | 执行计划生成、分层 | 100% 可控 |
| **PlanExecutor** | 分层执行、并行控制 | 100% 可控 |
| **ToolResultStore** | 存储和提供依赖结果 | 100% 可控 |
| **ToolExecutionMetrics** | 性能指标收集（每次执行独立） | 100% 可控 |

### 2.3 与现有架构的关系

```
现有架构：
  SubAgentToolLoop (接口)
    ├── SerialToolLoop (串行，matchIfMissing=true)
    └── BatchToolLoop (批量并行)

新增：
    └── HybridToolLoop (混合DAG)

配置切换：
  agent.subtask.tool-loop=serial    # 串行（默认）
  agent.subtask.tool-loop=batch     # 批量并行
  agent.subtask.tool-loop=hybrid    # 混合DAG
```

---

## 三、核心组件设计

### 3.1 依赖关系定义

#### 3.1.1 注解定义

```java
/**
 * 工具依赖声明注解
 *
 * 示例：
 * @DependsOn(toolName = {"queryWeather", "queryBlog"})
 * public ItineraryResult generateItinerary(String city) { ... }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DependsOn {
    /**
     * 依赖的工具名称列表
     * 工具名称对应 @Tool 注解的 name 属性或方法名
     */
    String[] toolName();
}

/**
 * 参数来源注解（可选，用于显式指定参数来源）
 *
 * 当类型推断不明确时（如多个工具返回相同类型），使用此注解明确指定
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface FromTool {
    /** 来源工具名称 */
    String value();
}
```

> **@Tool 注解 name 属性**：Spring AI 的 `@Tool` 注解支持 `name` 属性，用于显式指定工具名称。
> 当 `name` 为空时，默认使用方法名。显式命名可以避免重构方法名时影响 LLM 的工具选择。
>
> 示例：`@Tool(name = "queryWeather")` 或 `@Tool`（使用方法名）

#### 3.1.2 工具定义示例

```java
// 无依赖
@Tool
public WeatherResult queryWeather(String city) {
    return new WeatherResult("晴天", 25);
}

// 有依赖 - 类型自动推断
@Tool
@DependsOn(toolName = {"queryWeather", "queryBlog"})
public ItineraryResult generateItinerary(
    String city,                    // Agent 参数（基本类型）
    WeatherResult weather,          // 依赖工具（类型自动推断）
    BlogResult blog                 // 依赖工具（类型自动推断）
) {
    return new ItineraryResult("上午故宫，下午颐和园");
}

// 有依赖 - 显式指定来源（类型冲突时使用）
@Tool
@DependsOn(toolName = {"queryUserLocation", "queryWeather"})
public ItineraryResult generateItinerary2(
    String city,
    @FromTool("queryUserLocation") LocationResult location,
    @FromTool("queryWeather") WeatherResult weather
) {
    return new ItineraryResult("推荐去" + location.getCity());
}
```

#### 3.1.3 参数匹配优先级

1. **`@FromTool` 注解**：显式指定来源工具（最高优先级）
2. **类型匹配**：参数类型是某个依赖工具的返回类型
3. **参数名匹配**：参数名与依赖工具名相同（需要 `-parameters` 编译选项）
4. **Agent 参数**：基本类型（String, int, boolean 等）默认从 LLM 规划中获取

---

### 3.2 依赖图构建器

#### 3.2.1 数据结构

```java
/**
 * 工具元数据
 */
@Data
@Builder
public class ToolMetadata {
    /** 工具名称 */
    private String name;
    
    /** 工具方法 */
    private Method method;
    
    /** 返回类型 */
    private Class<?> returnType;
    
    /** 依赖的工具名称列表 */
    private List<String> dependencies;
    
    /** 参数列表 */
    private List<ParameterInfo> parameters;
    
    /** 是否标记为 SequentialOnly（禁止并行） */
    private boolean sequentialOnly;
}

/**
 * 参数信息
 */
@Data
@Builder
public class ParameterInfo {
    /** 参数名 */
    private String name;
    
    /** 参数类型 */
    private Class<?> type;
    
    /** 是否来自依赖工具 */
    private boolean fromDependency;
    
    /** 依赖的工具名称（fromDependency=true 时） */
    private String dependencyToolName;
    
    /** 依赖工具的返回类型（用于类型校验） */
    private Class<?> dependencyReturnType;
    
    /** 是否显式指定来源（@FromTool） */
    private boolean explicitSource;
}
```

#### 3.2.2 构建结果

```java
/**
 * 依赖图构建结果
 */
@Data
@Builder
public class GraphBuildResult {
    /** 依赖图 */
    private Map<String, List<String>> graph;
    
    /** 未注册的工具列表 */
    private List<String> unknownTools;
    
    /**
     * 是否有效（无未知工具）
     */
    public boolean isValid() {
        return unknownTools.isEmpty();
    }
}
```

#### 3.2.3 构建器实现

```java
@Component
@Slf4j
public class GraphAnalyzer {
    
    private final Map<String, ToolMetadata> toolMetadataMap = new ConcurrentHashMap<>();
    
    /**
     * 扫描并注册工具元数据
     */
    public void scanAndRegister(List<Object> toolInstances) {
        for (Object toolInstance : toolInstances) {
            Class<?> clazz = toolInstance.getClass();
            for (Method method : clazz.getMethods()) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                if (toolAnnotation == null) continue;
                
                // 优先使用 @Tool 注解的 name 属性，否则使用方法名
                String toolName = toolAnnotation.name().isEmpty() 
                    ? method.getName() 
                    : toolAnnotation.name();
                DependsOn dependsOn = method.getAnnotation(DependsOn.class);
                List<String> dependencies = dependsOn != null 
                    ? Arrays.asList(dependsOn.toolName()) 
                    : List.of();
                
                List<ParameterInfo> parameters = analyzeParameters(method, dependencies);
                
                // 检查是否标记为顺序执行
                SequentialOnly seqOnly = method.getAnnotation(SequentialOnly.class);
                
                ToolMetadata metadata = ToolMetadata.builder()
                    .name(toolName)
                    .method(method)
                    .returnType(method.getReturnType())
                    .dependencies(dependencies)
                    .parameters(parameters)
                    .sequentialOnly(seqOnly != null)
                    .build();
                
                toolMetadataMap.put(toolName, metadata);
                log.info("注册工具: {} -> {}, 依赖: {}, 顺序执行: {}", 
                    toolName, method.getReturnType().getSimpleName(), 
                    dependencies, seqOnly != null);
            }
        }
        
        // 验证依赖关系
        validateDependencies();
    }
    
    /**
     * 验证所有工具的依赖关系
     */
    private void validateDependencies() {
        for (ToolMetadata meta : toolMetadataMap.values()) {
            for (String dep : meta.getDependencies()) {
                if (!toolMetadataMap.containsKey(dep)) {
                    log.warn("工具 {} 依赖的工具 {} 尚未注册", meta.getName(), dep);
                }
            }
            
            // 检查返回类型是否唯一
            for (ToolMetadata existing : toolMetadataMap.values()) {
                if (existing.getReturnType().equals(meta.getReturnType()) 
                    && !existing.getName().equals(meta.getName())) {
                    log.warn("工具 {} 和 {} 返回相同类型 {}，可能导致依赖注入歧义", 
                        meta.getName(), existing.getName(), meta.getReturnType().getSimpleName());
                }
            }
        }
    }
    
    /**
     * 分析参数：自动推断哪些参数来自依赖工具
     */
    private List<ParameterInfo> analyzeParameters(Method method, List<String> dependencies) {
        List<ParameterInfo> params = new ArrayList<>();
        Parameter[] methodParams = method.getParameters();
        
        for (Parameter param : methodParams) {
            Class<?> type = param.getType();
            
            // 1. 检查 @FromTool 注解
            FromTool fromTool = param.getAnnotation(FromTool.class);
            if (fromTool != null) {
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(fromTool.value())
                    .explicitSource(true)
                    .build());
                continue;
            }
            
            // 2. 类型匹配
            String dependencyTool = findDependencyByReturnType(type, dependencies);
            if (dependencyTool != null) {
                // 获取依赖工具的返回类型
                ToolMetadata depMeta = toolMetadataMap.get(dependencyTool);
                Class<?> depReturnType = depMeta != null ? depMeta.getReturnType() : null;
                
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(dependencyTool)
                    .dependencyReturnType(depReturnType)
                    .explicitSource(false)
                    .build());
                continue;
            }
            
            // 3. 参数名匹配（需要 -parameters 编译选项）
            String paramDep = findDependencyByParamName(param.getName(), dependencies);
            if (paramDep != null) {
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(paramDep)
                    .explicitSource(false)
                    .build());
                continue;
            }
            
            // 4. Agent 参数
            params.add(ParameterInfo.builder()
                .name(param.getName())
                .type(type)
                .fromDependency(false)
                .build());
        }
        
        return params;
    }
    
    /**
     * 根据返回类型查找依赖工具
     */
    private String findDependencyByReturnType(Class<?> type, List<String> dependencies) {
        for (String dep : dependencies) {
            ToolMetadata depMeta = toolMetadataMap.get(dep);
            if (depMeta != null && type.isAssignableFrom(depMeta.getReturnType())) {
                return dep;
            }
        }
        return null;
    }
    
    /**
     * 根据参数名查找依赖工具
     */
    private String findDependencyByParamName(String paramName, List<String> dependencies) {
        for (String dep : dependencies) {
            if (paramName.equals(dep)) {
                return dep;
            }
        }
        return null;
    }
    
    /**
     * 构建依赖图（返回构建结果，包含未知工具信息）
     */
    public GraphBuildResult buildGraph(Collection<String> toolNames) {
        Map<String, List<String>> graph = new HashMap<>();
        List<String> unknownTools = new ArrayList<>();
        
        for (String toolName : toolNames) {
            ToolMetadata meta = toolMetadataMap.get(toolName);
            if (meta != null) {
                graph.put(toolName, meta.getDependencies());
            } else {
                unknownTools.add(toolName);
                log.warn("工具 {} 未注册", toolName);
            }
        }
        
        return GraphBuildResult.builder()
            .graph(graph)
            .unknownTools(unknownTools)
            .build();
    }
    
    /**
     * 获取工具元数据
     */
    public ToolMetadata getMetadata(String toolName) {
        return toolMetadataMap.get(toolName);
    }
    
    /**
     * 获取所有工具元数据
     */
    public Map<String, ToolMetadata> getAllMetadata() {
        return Collections.unmodifiableMap(toolMetadataMap);
    }
}
```

---

### 3.3 DAG 规划器

#### 3.3.1 数据结构

```java
/**
 * 执行计划
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
    
    /** 未注册的工具列表（buildGraph 返回） */
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
```

#### 3.3.2 规划器实现

```java
@Component
@Slf4j
public class PlanGenerator {
    
    private final GraphAnalyzer graphBuilder;
    
    /**
     * 生成执行计划
     */
    public ExecutionPlan plan(List<String> selectedTools) {
        // 1. 构建依赖图（包含未知工具检测）
        GraphBuildResult graphResult = graphBuilder.buildGraph(selectedTools);
        
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
                    ToolMetadata meta = graphBuilder.getMetadata(tool);
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
    
    @Data
    @Builder
    private static class ValidationResult {
        private boolean valid;
        private String message;
        
        public static ValidationResult valid() {
            return ValidationResult.builder().valid(true).build();
        }
        
        public static ValidationResult invalid(String message) {
            return ValidationResult.builder().valid(false).message(message).build();
        }
    }
}
```

---

### 3.4 依赖工具存储

#### 3.4.1 数据结构

```java
/**
 * 工具执行结果条目（封装结果和类型）
 */
@Data
@Builder
public class ToolResultEntry {
    /** 执行结果（失败时为 null） */
    private final Object result;
    
    /** 结果类型 */
    private final Class<?> type;
    
    /**
     * 获取类型化结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getTypedResult() {
        if (result == null) return null;
        return (T) type.cast(result);
    }
}
```

#### 3.4.2 接口定义

```java
/**
 * 依赖工具接口：存储和获取工具执行结果
 */
public interface ToolResultStore {
    
    /**
     * 存储工具执行结果（带类型信息）
     */
    void store(String toolName, Object result, Class<?> returnType);
    
    /**
     * 按索引获取当前层的结果
     * <p>搜索范围：当前层（currentEntries）</p>
     */
    <T> T get(int index);
    
    /**
     * 根据返回类型获取当前层的结果
     * <p>搜索范围：当前层（currentEntries），避免跨层结果泄露</p>
     */
    <T> T getByType(Class<T> type);
    
    /**
     * 根据工具名获取结果（带类型校验）
     * <p>搜索范围：全局（allResults），可跨层获取</p>
     */
    <T> T getByName(String toolName, Class<T> type);
    
    /**
     * 设置当前执行层的结果条目列表
     */
    void setCurrentLayerEntries(List<ToolResultEntry> entries);
    
    /**
     * 清空当前层上下文（层切换时调用）
     */
    void clearCurrentLayer();
    
    /**
     * 清空所有结果（新一轮执行时调用）
     */
    void clearAll();
    
    /**
     * 判断某个工具是否执行成功
     */
    boolean isSuccess(String toolName);
}
```

#### 3.4.3 实现

```java
@Component
@Slf4j
public class InMemoryToolResultStore implements ToolResultStore {
    
    /** 所有工具执行结果 */
    private final Map<String, Object> allResults = new ConcurrentHashMap<>();
    
    /** 失败原因 */
    private final Map<String, String> failedReasons = new ConcurrentHashMap<>();
    
    /** 工具名 → 返回类型映射 */
    private final Map<String, Class<?>> toolReturnTypes = new ConcurrentHashMap<>();
    
    /** 当前层结果条目（synchronized 锁保护） */
    private final Object layerLock = new Object();
    private List<ToolResultEntry> currentEntries;
    
    @Override
    public void store(String toolName, Object result, Class<?> returnType) {
        allResults.put(toolName, result);
        if (returnType != null) {
            toolReturnTypes.put(toolName, returnType);
        }
        log.debug("存储工具结果: {} -> {} (类型: {})", toolName, 
            result != null ? result.getClass().getSimpleName() : "null",
            returnType != null ? returnType.getSimpleName() : "未知");
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        synchronized (layerLock) {
            if (currentEntries == null || index >= currentEntries.size()) {
                throw new IndexOutOfBoundsException("当前层没有索引 " + index + " 的结果");
            }
            
            ToolResultEntry entry = currentEntries.get(index);
            if (entry.getResult() == null) {
                return null;  // 失败工具返回 null
            }
            
            return (T) entry.getType().cast(entry.getResult());
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getByType(Class<T> type) {
        // 只搜索当前层，避免跨层结果泄露
        synchronized (layerLock) {
            if (currentEntries == null) return null;
            return currentEntries.stream()
                .filter(e -> e.getResult() != null && type.isInstance(e.getResult()))
                .map(e -> (T) type.cast(e.getResult()))
                .findFirst()
                .orElse(null);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getByName(String toolName, Class<T> type) {
        // 搜索全局结果（跨层）
        Object result = allResults.get(toolName);
        if (result == null) return null;
        
        // 类型校验
        Class<?> actualType = toolReturnTypes.get(toolName);
        if (actualType != null && !type.isAssignableFrom(actualType)) {
            log.warn("工具 {} 返回类型 {} 与请求类型 {} 不匹配", 
                toolName, actualType.getSimpleName(), type.getSimpleName());
            return null;
        }
        
        return type.cast(result);
    }
    
    @Override
    public void setCurrentLayerEntries(List<ToolResultEntry> entries) {
        synchronized (layerLock) {
            this.currentEntries = entries;
        }
    }
    
    @Override
    public void clearCurrentLayer() {
        synchronized (layerLock) {
            this.currentEntries = null;
        }
    }
    
    @Override
    public void clearAll() {
        allResults.clear();
        failedReasons.clear();
        toolReturnTypes.clear();
        clearCurrentLayer();
    }
    
    @Override
    public boolean isSuccess(String toolName) {
        // 工具执行成功但返回 null（如删除操作）也算成功
        return allResults.containsKey(toolName) && !failedReasons.containsKey(toolName);
    }
    
    /**
     * 记录失败原因
     */
    public void recordFailure(String toolName, String reason) {
        failedReasons.put(toolName, reason);
    }
    
    /**
     * 获取失败原因
     */
    public String getFailureReason(String toolName) {
        return failedReasons.get(toolName);
    }
    
    /**
     * 注册工具返回类型（启动时调用）
     */
    public void registerReturnType(String toolName, Class<?> returnType) {
        toolReturnTypes.put(toolName, returnType);
    }
}
```

---

### 3.5 策略接口

#### 3.5.1 重试策略接口

```java
/**
 * 重试策略接口
 */
public interface RetryStrategy {
    
    /**
     * 判断是否应该重试
     */
    boolean shouldRetry(Exception e, int attempt);
    
    /**
     * 获取重试延迟时间（毫秒）
     */
    long getRetryDelay(int attempt);
}

/**
 * 指数退避重试策略（默认）
 */
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.retry.strategy", havingValue = "exponential", matchIfMissing = true)
public class ExponentialBackoffRetryStrategy implements RetryStrategy {
    
    private final DagProperties dagProperties;
    
    @Override
    public boolean shouldRetry(Exception e, int attempt) {
        if (attempt >= dagProperties.getRetry().getMaxRetries()) {
            return false;
        }
        String exceptionName = e.getClass().getName();
        String simpleName = e.getClass().getSimpleName();
        return dagProperties.getRetry().getRetryableErrors().stream()
            .anyMatch(r -> exceptionName.equals(r) || simpleName.equals(r));
    }
    
    @Override
    public long getRetryDelay(int attempt) {
        return dagProperties.getRetry().getBaseDelayMs() * (1L << attempt);
    }
}

/**
 * 无重试策略
 */
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.retry.strategy", havingValue = "none")
public class NoRetryStrategy implements RetryStrategy {
    
    @Override
    public boolean shouldRetry(Exception e, int attempt) {
        return false;
    }
    
    @Override
    public long getRetryDelay(int attempt) {
        return 0;
    }
}
```

#### 3.5.2 超时策略接口

```java
/**
 * 超时策略接口
 */
public interface TimeoutStrategy {
    
    /**
     * 带超时执行任务
     */
    <T> T executeWithTimeout(Supplier<T> task, long timeoutMs) throws Exception;
}

/**
 * Future 超时策略（默认，使用 CompletableFuture.orTimeout）
 */
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.timeout.strategy", havingValue = "future", matchIfMissing = true)
public class FutureTimeoutStrategy implements TimeoutStrategy {
    
    private final Executor aiTaskExecutor;
    
    @Override
    public <T> T executeWithTimeout(Supplier<T> task, long timeoutMs) throws Exception {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, aiTaskExecutor)
            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}

/**
 * 无超时策略（直接执行，不设置超时）
 */
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.timeout.strategy", havingValue = "none")
public class NoTimeoutStrategy implements TimeoutStrategy {
    
    @Override
    public <T> T executeWithTimeout(Supplier<T> task, long timeoutMs) throws Exception {
        return task.get();
    }
}
```

#### 3.5.3 结果压缩接口

```java
/**
 * 结果压缩接口
 */
public interface ToolResultCompressor {
    
    /**
     * 压缩工具执行结果
     */
    String compress(String raw, String toolName, int maxLength);
}

/**
 * 截断压缩策略（默认）
 */
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.compressor", havingValue = "truncate", matchIfMissing = true)
public class TruncateCompressor implements ToolResultCompressor {
    
    @Override
    public String compress(String raw, String toolName, int maxLength) {
        if (raw == null) return "";
        if (raw.length() <= maxLength) return raw;
        return raw.substring(0, maxLength) + "...";
    }
}

/**
 * LLM 压缩策略（预留，使用 LLM 生成摘要）
 */
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.compressor", havingValue = "llm")
public class LlmCompressor implements ToolResultCompressor {
    
    // TODO: 实现 LLM 压缩逻辑
    
    @Override
    public String compress(String raw, String toolName, int maxLength) {
        // 降级为截断
        if (raw == null) return "";
        if (raw.length() <= maxLength) return raw;
        return raw.substring(0, maxLength) + "...";
    }
}
```

---

### 3.6 DAG 执行器

#### 3.6.1 执行器接口

```java
/**
 * DAG 执行器接口
 */
public interface PlanExecutor {
    
    /**
     * 执行 DAG 计划
     */
    DagExecutionResult execute(ExecutionPlan plan, Map<String, ToolInvoker> tools);
}
```

#### 3.6.2 本地执行器实现

```java
/**
 * 工具调用器接口
 */
@FunctionalInterface
public interface ToolInvoker {
    /**
     * 执行工具
     * @return 工具执行结果
     */
    Object invoke() throws Exception;
    
    /**
     * 获取工具返回类型（默认 Object）
     */
    default Class<?> getReturnType() {
        return Object.class;
    }
}
```

#### 3.5.2 超时配置

```java
/**
 * DAG 执行配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.subtask.dag")
public class DagProperties {
    
    /** 层级超时（秒） */
    private int layerTimeoutSeconds = 30;
    
    /** 单工具超时（秒）- 默认值 */
    private int toolTimeoutSeconds = 10;
    
    /** 按工具名配置的超时（优先级高于默认值） */
    private Map<String, Integer> toolTimeouts = Map.of();
    
    /**
     * 获取工具超时配置（按工具名优先，否则用默认值）
     */
    public int getToolTimeout(String toolName) {
        return toolTimeouts.getOrDefault(toolName, toolTimeoutSeconds);
    }
    
    /** 是否启用重试 */
    private boolean retryEnabled = true;
    
    /** 最大重试次数 */
    private int maxRetries = 3;
    
    /** 重试基础延迟（毫秒） */
    private long retryBaseDelayMs = 1000;
    
    /** 可重试的异常类名 */
    private List<String> retryableErrors = List.of(
        "java.net.SocketTimeoutException",
        "java.net.ConnectException"
    );
}
```

#### 3.5.3 执行器实现

```java
@Component
@Slf4j
public class DefaultPlanExecutor implements PlanExecutor {
    
    private final ToolResultStore dependencyTool;
    private final Executor aiTaskExecutor;
    private final DagProperties dagProperties;
    private final RetryStrategy retryStrategy;
    private final TimeoutStrategy timeoutStrategy;
    
    /**
     * 执行 DAG 计划
     */
    public DagExecutionResult execute(ExecutionPlan plan, Map<String, ToolInvoker> tools) {
        if (!plan.isValid()) {
            return DagExecutionResult.failed(plan.getInvalidReason());
        }
        
        long startTime = System.currentTimeMillis();
        Map<String, Object> results = new ConcurrentHashMap<>();
        Map<String, String> failedReasons = new ConcurrentHashMap<>();
        List<String> executedTools = new ArrayList<>();
        List<String> failedTools = new ArrayList<>();
        List<ToolExecutionMetrics> metrics = new CopyOnWriteArrayList<>();
        
        try {
            for (int i = 0; i < plan.getLayers().size(); i++) {
                List<String> layer = plan.getLayers().get(i);
                log.info("执行 Layer {}: {}", i, layer);
                
                // 使用 ToolResultEntry 包裹结果和类型
                List<ToolResultEntry> layerEntries = new CopyOnWriteArrayList<>();
                
                // 设置当前层上下文
                dependencyTool.setCurrentLayerEntries(layerEntries);
                
                // 并行执行当前层
                List<CompletableFuture<Void>> futures = layer.stream()
                    .map(toolName -> CompletableFuture.runAsync(() -> {
                        long toolStartTime = System.currentTimeMillis();
                        
                        try {
                            // 执行工具（带重试和超时）
                            ToolInvoker invoker = tools.get(toolName);
                            Object result = executeWithRetryAndTimeout(toolName, invoker);
                            
                            // 存储结果（带类型信息）
                            results.put(toolName, result);
                            dependencyTool.store(toolName, result, invoker.getReturnType());
                            
                            // 记录到当前层（使用 ToolResultEntry）
                            layerEntries.add(ToolResultEntry.builder()
                                .result(result)
                                .type(invoker.getReturnType())
                                .build());
                            
                            executedTools.add(toolName);
                            long duration = System.currentTimeMillis() - toolStartTime;
                            log.debug("工具执行完成: {} ({}ms)", toolName, duration);
                            
                            // 记录指标（本次执行独立收集）
                            metrics.add(ToolExecutionMetrics.builder()
                                .toolName(toolName)
                                .duration(duration)
                                .success(true)
                                .layer(i)
                                .executedAt(LocalDateTime.now())
                                .build());
                            
                        } catch (Exception e) {
                            log.error("工具执行失败: {}", toolName, e);
                            failedTools.add(toolName);
                            failedReasons.put(toolName, e.getMessage());
                            
                            // 失败时返回 null，保持类型一致（使用 ToolResultEntry）
                            ToolInvoker invoker = tools.get(toolName);
                            results.put(toolName, null);
                            dependencyTool.store(toolName, null, invoker.getReturnType());  // 失败也要存储类型信息
                            layerEntries.add(ToolResultEntry.builder()
                                .result(null)
                                .type(invoker.getReturnType())
                                .build());
                            
                            // 记录失败指标
                            long duration = System.currentTimeMillis() - toolStartTime;
                            metrics.add(ToolExecutionMetrics.builder()
                                .toolName(toolName)
                                .duration(duration)
                                .success(false)
                                .errorMessage(e.getMessage())
                                .layer(i)
                                .executedAt(LocalDateTime.now())
                                .build());
                        }
                    }, aiTaskExecutor))
                    .collect(Collectors.toList());
                
                // 等待当前层完成（带超时）
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(dagProperties.getLayerTimeoutSeconds(), TimeUnit.SECONDS)
                        .join();
                } catch (TimeoutException e) {
                    log.error("Layer {} 执行超时 ({}s)，取消未完成任务", 
                        i, dagProperties.getLayerTimeoutSeconds());
                    // 取消所有未完成的 futures
                    futures.forEach(f -> f.cancel(true));
                    
                    return DagExecutionResult.builder()
                        .success(false)
                        .results(results)
                        .failedReasons(failedReasons)
                        .executedTools(executedTools)
                        .failedTools(failedTools)
                        .metrics(metrics)
                        .duration(System.currentTimeMillis() - startTime)
                        .errorMessage("Layer " + i + " 执行超时")
                        .build();
                } catch (CompletionException e) {
                    log.error("Layer {} 执行异常", i, e);
                    // 继续处理其他异常
                }
                
                // 清空当前层上下文
                dependencyTool.clearCurrentLayer();
                
                log.info("Layer {} 完成", i);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            boolean success = failedTools.isEmpty();
            
            return DagExecutionResult.builder()
                .success(success)
                .results(results)
                .failedReasons(failedReasons)
                .executedTools(executedTools)
                .failedTools(failedTools)
                .metrics(metrics)
                .duration(duration)
                .build();
            
        } catch (Exception e) {
            log.error("DAG 执行异常", e);
            long duration = System.currentTimeMillis() - startTime;
            return DagExecutionResult.builder()
                .success(false)
                .results(results)
                .failedReasons(failedReasons)
                .executedTools(executedTools)
                .failedTools(failedTools)
                .metrics(metrics)
                .duration(duration)
                .errorMessage("执行异常: " + e.getMessage())
                .build();
        }
    }
    
    /**
     * 执行工具（带重试和超时）
     * 重试循环在外层，Thread.sleep 不会阻塞线程池线程
     */
    private Object executeWithRetryAndTimeout(String toolName, ToolInvoker invoker) 
            throws Exception {
        long timeoutMs = dagProperties.getToolTimeout(toolName) * 1000L;  // 支持按工具名配置
        int maxRetries = dagProperties.getRetry().getMaxRetries();
        
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return timeoutStrategy.executeWithTimeout(() -> {
                    try {
                        return invoker.invoke();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, timeoutMs);
            } catch (Exception e) {
                lastException = e;
                if (retryStrategy.shouldRetry(e, attempt)) {
                    long delay = retryStrategy.getRetryDelay(attempt);
                    log.warn("工具 {} 第 {} 次重试，等待 {}ms: {}", 
                        toolName, attempt + 1, delay, e.getMessage());
                    Thread.sleep(delay);  // 外层 sleep，不影响其他工具
                }
            }
        }
        
        throw lastException;
    }
}
```

#### 3.6.3 执行结果

```java
@Data
@Builder
public class DagExecutionResult {
    /** 是否成功 */
    private boolean success;
    
    /** 所有工具结果 */
    private Map<String, Object> results;
    
    /** 失败原因（默认空 Map，避免 failed() 返回时 NPE） */
    @Builder.Default
    private Map<String, String> failedReasons = Map.of();
    
    /** 已执行的工具 */
    private List<String> executedTools;
    
    /** 失败的工具 */
    private List<String> failedTools;
    
    /** 执行耗时（毫秒） */
    private long duration;
    
    /** 错误信息 */
    private String errorMessage;
    
    /** 本次执行的指标列表（每次执行独立收集，不共享） */
    @Builder.Default
    private List<ToolExecutionMetrics> metrics = List.of();
    
    public static DagExecutionResult success(Map<String, Object> results, 
                                              List<String> executed, 
                                              List<String> failed,
                                              Map<String, String> failedReasons,
                                              List<ToolExecutionMetrics> metrics,
                                              long duration) {
        return DagExecutionResult.builder()
            .success(failed.isEmpty())
            .results(results)
            .executedTools(executed)
            .failedTools(failed)
            .failedReasons(failedReasons)
            .metrics(metrics)
            .duration(duration)
            .build();
    }
    
    public static DagExecutionResult failed(String errorMessage) {
        return DagExecutionResult.builder()
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
    
    /**
     * 判断某个工具是否执行成功
     * 工具执行成功但返回 null（如删除操作）也算成功
     */
    public boolean isSuccess(String toolName) {
        return results.containsKey(toolName) && 
               (failedReasons == null || !failedReasons.containsKey(toolName));
    }
    
    /**
     * 获取指定工具的结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getResult(String toolName, Class<T> type) {
        Object result = results.get(toolName);
        if (result == null) return null;
        return type.cast(result);
    }
}
```

---

### 3.6 指标收集器

```java
/**
 * 工具执行指标
 */
@Data
@Builder
public class ToolExecutionMetrics {
    /** 工具名称 */
    private String toolName;
    
    /** 执行耗时（毫秒） */
    private long duration;
    
    /** 是否成功 */
    private boolean success;
    
    /** 错误信息 */
    private String errorMessage;
    
    /** 层级 */
    private int layer;
    
    /** 执行时间戳 */
    private LocalDateTime executedAt;
    
    /**
     * 指标统计工具类（每次执行独立使用，非单例）
     */
    public static class Stats {
        private static final Logger log = LoggerFactory.getLogger(ToolExecutionMetrics.Stats.class);
        
        /**
         * 计算平均执行时间
         */
        public static double averageDuration(List<ToolExecutionMetrics> metrics) {
            return metrics.stream()
                .mapToLong(ToolExecutionMetrics::getDuration)
                .average()
                .orElse(0.0);
        }
        
        /**
         * 计算成功率
         */
        public static double successRate(List<ToolExecutionMetrics> metrics) {
            if (metrics.isEmpty()) return 1.0;
            long successCount = metrics.stream()
                .filter(ToolExecutionMetrics::isSuccess)
                .count();
            return (double) successCount / metrics.size();
        }
        
        /**
         * 记录指标日志
         */
        public static void logMetrics(List<ToolExecutionMetrics> metrics) {
            metrics.forEach(m -> 
                log.debug("指标: {} - {}ms - {}", 
                    m.getToolName(), m.getDuration(), 
                    m.isSuccess() ? "成功" : "失败")
            );
        }
    }
}
```

> **设计说明**：指标收集采用"每次执行独立收集"模式，`PlanExecutor.execute()` 方法内创建 `List<ToolExecutionMetrics>`，执行完成后随 `DagExecutionResult` 返回。HybridToolLoop 通过 `result.getMetrics()` 获取本次执行的指标，可选择记录日志或上报。不再使用单例 `MetricsCollector` Bean，避免多轮执行指标混淆。

---

### 3.7 审查 LLM（预留）

```java
/**
 * 工具规划审查器接口（预留）
 */
public interface PlanReviewer {
    
    /**
     * 审查工具规划
     */
    ReviewResult review(List<String> selectedTools, UserInput input);
    
    @Data
    @Builder
    class ReviewResult {
        /** 是否通过 */
        private boolean approved;
        
        /** 审查意见 */
        private String reason;
        
        /** 建议修改的工具列表（可选） */
        private List<String> suggestedTools;
    }
}

@Component
@ConditionalOnProperty(name = "agent.subtask.plan-reviewer-enabled", havingValue = "true")
@Slf4j
public class LlmPlanReviewer implements PlanReviewer {
    
    private final ChatModel chatModel;
    
    @Override
    public ReviewResult review(List<String> selectedTools, UserInput input) {
        // TODO: 实现 LLM 审查逻辑
        log.info("LLM 审查预留，当前直接通过");
        return ReviewResult.builder()
            .approved(true)
            .reason("审查功能预留")
            .build();
    }
}
```

---

### 3.8 顺序执行注解

```java
/**
 * 标记工具必须顺序执行（禁止并行）
 * 
 * 某些工具可能不适合并行执行（如需要严格顺序的文件操作）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SequentialOnly {
    /** 原因说明 */
    String reason() default "";
}

// 使用示例
@Tool
@SequentialOnly(reason = "文件操作需要严格顺序")
public FileResult writeToFile(String content) { ... }
```

---

## 四、与现有架构集成

### 4.1 HybridToolLoop

```java
/**
 * 混合 DAG 执行策略
 *
 * 配置：agent.subtask.tool-loop=hybrid
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.tool-loop", havingValue = "hybrid")
public class HybridToolLoop extends AbstractToolLoop {
    
    @Resource
    private GraphAnalyzer graphBuilder;
    
    @Resource
    private PlanGenerator dagPlanner;
    
    @Resource
    private PlanExecutor dagExecutor;
    
    @Resource
    private ToolResultStore dependencyTool;
    
    @Resource
    private ToolResultCompressor compressor;  // 结果压缩器
    
    @Resource
    private SubTaskProperties subTaskProperties;  // 配置属性
    
    @Resource(required = false)
    private PlanReviewer planReviewer;  // 可选
    
    @Override
    public String toolCallRule() {
        return "根据依赖关系自动编排执行顺序，无依赖的工具并行执行";
    }
    
    @Override
    protected ToolResponseMessage executeRound(AssistantMessage out, SubAgentToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining,
            AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey) {
        
        // 1. 解析 LLM 返回的工具调用
        List<String> selectedTools = out.getToolCalls().stream()
            .map(AssistantMessage.ToolCall::name)
            .collect(Collectors.toList());
        
        // 2. 审查（可选）
        if (planReviewer != null) {
            PlanReviewer.ReviewResult review = planReviewer.review(selectedTools, null);
            if (!review.isApproved()) {
                log.warn("规划审查未通过: {}", review.getReason());
                if (review.getSuggestedTools() != null) {
                    selectedTools = review.getSuggestedTools();
                }
            }
        }
        
        // 3. 生成执行计划
        ExecutionPlan plan = dagPlanner.plan(selectedTools);
        
        if (!plan.isValid()) {
            log.warn("执行计划无效: {}，降级到串行执行", plan.getInvalidReason());
            return fallbackSerialExecution(out, ctx, doneSummary, remaining, callCounter);
        }
        
        // 4. 构建工具调用器（包含参数注入逻辑）
        Map<String, ToolInvoker> tools = buildToolInvokers(out.getToolCalls(), ctx);
        
        // 5. 清空上次执行结果
        dependencyTool.clearAll();
        
        // 6. 执行 DAG
        DagExecutionResult result = dagExecutor.execute(plan, tools);
        
        // 7. 转换为 ToolResponseMessage
        return buildToolResponse(out.getToolCalls(), result, doneSummary, remaining, callCounter);
    }
    
    /**
     * 降级到串行执行
     */
    private ToolResponseMessage fallbackSerialExecution(AssistantMessage out, SubAgentToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining, AtomicInteger callCounter) {
        // 调用 SerialToolLoop 的逻辑
        log.warn("降级到串行执行");
        // ...（复用 SerialToolLoop 逻辑）
        return null;
    }
    
    /**
     * 构建工具调用器映射
     * 
     * <p>参数注入流程：</p>
     * <ol>
     *   <li>LLM 返回的 tool_calls 包含 Agent 参数（JSON 格式）</li>
     *   <li>ToolCallback.call() 内部负责 JSON 反序列化为方法参数</li>
     *   <li>依赖工具的参数由 ToolResultStore.get() / getByType() 在工具内部获取</li>
     *   <li>工具方法内部通过参数类型自动推断获取依赖结果</li>
     * </ol>
     */
    private Map<String, ToolInvoker> buildToolInvokers(
            List<AssistantMessage.ToolCall> toolCalls, SubAgentToolLoopContext ctx) {
        
        Map<String, ToolInvoker> invokers = new HashMap<>();
        ToolContext toolCtx = new ToolContext(ctx.toolContext() == null ? Map.of() : ctx.toolContext());
        
        for (AssistantMessage.ToolCall tc : toolCalls) {
            ToolCallback cb = findByName(ctx.callbacks(), tc.name());
            if (cb != null) {
                ToolMetadata meta = graphBuilder.getMetadata(tc.name());
                invokers.put(tc.name(), new ToolInvoker() {
                    @Override
                    public Object invoke() throws Exception {
                        // 1. 解析 Agent 参数（从 JSON，由 Spring AI ToolCallback 处理）
                        // 2. 依赖参数由工具内部通过 ToolResultStore.get(index) 获取
                        // 3. 合并后执行工具方法
                        return cb.call(tc.arguments(), toolCtx);
                    }
                    
                    @Override
                    public Class<?> getReturnType() {
                        return meta != null ? meta.getReturnType() : Object.class;
                    }
                });
            }
        }
        
        return invokers;
    }
    
    /**
     * 构建工具响应
     */
    private ToolResponseMessage buildToolResponse(
            List<AssistantMessage.ToolCall> toolCalls,
            DagExecutionResult result,
            Map<String, String> doneSummary,
            List<SubTask> remaining,
            AtomicInteger callCounter) {
        
        int compressLength = subTaskProperties.getCompressLength();  // 从配置读取
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        
        for (AssistantMessage.ToolCall tc : toolCalls) {
            Object rawResult = result.getResults().get(tc.name());
            String compact;
            
            if (rawResult == null) {
                // 失败工具：提供错误信息
                String reason = result.getFailedReasons() != null 
                    ? result.getFailedReasons().get(tc.name()) 
                    : "工具执行失败";
                compact = "错误：" + reason;
            } else {
                // 成功工具：压缩结果
                String rawStr = rawResult.toString();
                compact = compressor.compress(rawStr, tc.name(), compressLength);
            }
            
            responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), compact));
            doneSummary.put(tc.name(), TextUtils.truncate(compact, 50));
            callCounter.incrementAndGet();
            removeExecuted(remaining, tc.name());
        }
        
        return ToolResponseMessage.builder().responses(responses).build();
    }
}
```

---

## 五、配置项

### 5.1 application.yml

```yaml
agent:
  subtask:
    tool-loop: hybrid          # 混合 DAG 模式
    plan-reviewer-enabled: false  # 暂不启用 LLM 审查
    
    dag:
      executor: local              # 执行器类型：local / distributed
      layer-timeout-seconds: 30    # 层级超时
      tool-timeout-seconds: 10     # 单工具超时（默认值）
      tool-timeouts:               # 按工具名配置超时（优先级高于默认值）
        queryWeather: 15           # 网络请求 15s
        queryBlog: 15
        generateItinerary: 5       # 计算任务 5s
      retry:
        strategy: exponential      # 重试策略：exponential / none
        max-retries: 3             # 最大重试次数
        base-delay-ms: 1000        # 重试基础延迟
        retryable-errors:          # 可重试的异常
          - SocketTimeoutException # 支持简写类名
          - ConnectException
      timeout:
        strategy: future           # 超时策略：future / none
      compressor: truncate         # 压缩策略：truncate / llm
    
    thread-pool:
      core-pool-size: 4            # 核心线程数
      max-pool-size: 8             # 最大线程数
      queue-capacity: 100          # 队列容量
      keep-alive-seconds: 60       # 空闲线程存活时间
      thread-name-prefix: "dag-executor-"  # 线程名前缀
    
    parallel-tools: true           # 同层并行
    parallel-compress: true        # 结果压缩并行
    max-tool-rounds: 6             # 最大轮数
    max-total-calls: 10            # 最大调用数
    compress-length: 80            # 结果压缩长度
```

### 5.2 配置类

```java
@Data
@Component
@ConfigurationProperties(prefix = "agent.subtask.dag")
public class DagProperties {
    /** 执行器类型：local / distributed */
    private String executor = "local";
    
    /** 层级超时（秒） */
    private int layerTimeoutSeconds = 30;
    
    /** 单工具超时（秒）- 默认值 */
    private int toolTimeoutSeconds = 10;
    
    /** 按工具名配置的超时（优先级高于默认值） */
    private Map<String, Integer> toolTimeouts = Map.of();
    
    /** 压缩策略：truncate / llm */
    private String compressor = "truncate";
    
    private RetryProperties retry = new RetryProperties();
    private TimeoutProperties timeout = new TimeoutProperties();
    
    /**
     * 获取工具超时配置（按工具名优先，否则用默认值）
     */
    public int getToolTimeout(String toolName) {
        return toolTimeouts.getOrDefault(toolName, toolTimeoutSeconds);
    }
    
    @Data
    public static class RetryProperties {
        /** 重试策略：exponential / none */
        private String strategy = "exponential";
        private boolean enabled = true;
        private int maxRetries = 3;
        private long baseDelayMs = 1000;
        private List<String> retryableErrors = List.of();
    }
    
    @Data
    public static class TimeoutProperties {
        /** 超时策略：future / none */
        private String strategy = "future";
    }
}

@Data
@Component
@ConfigurationProperties(prefix = "agent.subtask.thread-pool")
public class ThreadPoolProperties {
    private int corePoolSize = 4;
    private int maxPoolSize = 8;
    private int queueCapacity = 100;
    private int keepAliveSeconds = 60;
    private String threadNamePrefix = "dag-executor-";
}

@Configuration
public class ThreadPoolConfig {
    
    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor(ThreadPoolProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadNamePrefix(properties.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

---

## 六、执行流程详解

### 6.1 完整流程图

```
用户输入："帮我规划周末"
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 1: LLM 选择工具                                       │
│                                                             │
│ Prompt: 可用工具列表 + 用户问题                              │
│ 输出: toolCalls = [                                         │
│   {name: "queryWeather", params: {city: "北京"}},          │
│   {name: "queryBlog", params: {city: "北京"}},             │
│   {name: "generateItinerary", params: {city: "北京"}},     │
│   {name: "sendNotification", params: {userId: "123"}}      │
│ ]                                                           │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 2: 规划审查（可选）                                    │
│                                                             │
│ 当前：跳过（plan-reviewer-enabled=false）                   │
│ 未来：调用审查 LLM 检查规划合理性                            │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 3: 依赖校验                                           │
│                                                             │
│ 1. 先检测循环依赖（结构性错误优先）                          │
│ 2. 再校验依赖完整性                                         │
│                                                             │
│ 静态依赖关系：                                              │
│ - queryWeather: []                                         │
│ - queryBlog: []                                            │
│ - generateItinerary: [queryWeather, queryBlog]             │
│ - sendNotification: [generateItinerary]                    │
│                                                             │
│ 校验：所有依赖都已被选中 ✓                                  │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 4: 拓扑排序分层                                       │
│                                                             │
│ Layer 0: [queryWeather, queryBlog]   ← 无依赖，并行        │
│ Layer 1: [generateItinerary]         ← 依赖 Layer 0        │
│ Layer 2: [sendNotification]          ← 依赖 Layer 1        │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 5: 分层执行                                           │
│                                                             │
│ Layer 0: 并行执行                                           │
│   ┌──────────────┐  ┌──────────────┐                       │
│   │ queryWeather │  │  queryBlog   │                       │
│   │  (线程1)     │  │  (线程2)     │                       │
│   │  超时: 10s   │  │  超时: 10s   │                       │
│   │  重试: 3次   │  │  重试: 3次   │                       │
│   └──────┬───────┘  └──────┬───────┘                       │
│          │                 │                               │
│          ▼                 ▼                               │
│   WeatherResult      BlogResult                           │
│                                                             │
│ Layer 0 整体超时: 30s                                       │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ Layer 1: 串行执行                                           │
│   ┌───────────────────────────────────────────────────────┐│
│   │              generateItinerary                        ││
│   │   自动注入: weather (WeatherResult), blog (BlogResult)││
│   │   超时: 10s, 重试: 3次                                ││
│   └───────────────────────────┬───────────────────────────┘│
│                               │                            │
│                               ▼                            │
│                        ItineraryResult                     │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ Layer 2: 串行执行                                           │
│   ┌───────────────────────────────────────────────────────┐│
│   │              sendNotification                         ││
│   │   自动注入: itinerary (ItineraryResult)               ││
│   │   超时: 10s, 重试: 3次                                ││
│   └───────────────────────────┬───────────────────────────┘│
│                               │                            │
│                               ▼                            │
│                      NotificationResult                    │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ Phase 6: 结果聚合                                           │
│                                                             │
│ 收集所有工具结果，构建 ToolResponseMessage                  │
│ 记录执行指标（耗时、成功率）                                 │
│ 返回给主循环继续处理                                        │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 参数注入流程

```
工具: generateItinerary(String city, WeatherResult weather, BlogResult blog)
                │
                ▼
┌─────────────────────────────────────────────────────────────┐
│ 参数分析（按优先级）                                         │
│                                                             │
│ 参数1: String city                                          │
│   → @FromTool: 无                                           │
│   → 类型匹配: String 不是工具返回类型                        │
│   → 参数名匹配: "city" ≠ 依赖工具名                         │
│   → 来源: Agent 参数（从 LLM 规划中获取）                   │
│                                                             │
│ 参数2: WeatherResult weather                                │
│   → @FromTool: 无                                           │
│   → 类型匹配: WeatherResult == queryWeather 返回类型 ✓      │
│   → 来源: ToolResultStore.getByType(WeatherResult.class)    │
│                                                             │
│ 参数3: BlogResult blog                                      │
│   → @FromTool: 无                                           │
│   → 类型匹配: BlogResult == queryBlog 返回类型 ✓            │
│   → 来源: ToolResultStore.getByType(BlogResult.class)       │
└─────────────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────┐
│ 执行工具                                                    │
│                                                             │
│ generateItinerary("北京", weatherResult, blogResult)       │
└─────────────────────────────────────────────────────────────┘
```

---

## 七、异常处理

### 7.1 异常场景

| 场景 | 处理方式 |
|------|----------|
| **LLM 规划错误**（依赖未选中） | 降级到串行执行 |
| **循环依赖** | 检测并报错 |
| **工具执行失败** | 记录错误，返回 null，继续执行后续工具 |
| **工具执行超时** | 取消任务，记录超时错误 |
| **依赖结果为 null** | 传递 null，由工具自行处理 |

### 7.2 降级策略

```java
/**
 * 降级执行：忽略依赖，按原始顺序串行执行
 */
private ToolResponseMessage fallbackSerialExecution(AssistantMessage out, SubAgentToolLoopContext ctx,
        Map<String, String> doneSummary, List<SubTask> remaining, AtomicInteger callCounter) {
    log.warn("降级到串行执行");
    
    // 复用 SerialToolLoop 的逻辑
    // ...
    
    return serialToolLoop.executeRound(out, ctx, doneSummary, remaining, callCounter, dupCounter, lastCallKey);
}
```

### 7.3 失败工具处理

失败工具返回 `null`，保持类型一致性：

```java
// 失败时（使用 ToolResultEntry）
results.put(toolName, null);
layerEntries.add(ToolResultEntry.builder()
    .result(null)
    .type(invoker.getReturnType())  // 保持原类型
    .build());

// 下游工具获取时
WeatherResult weather = dependencyTool.getByType(WeatherResult.class);
// 如果 queryWeather 失败，weather = null
// 工具内部需要处理 null 情况
```

---

## 八、待实现文件清单

### 8.1 注解定义

| 文件 | 路径 | 说明 |
|------|------|------|
| `DependsOn.java` | `agent/annotation/` | 依赖声明注解 |
| `FromTool.java` | `agent/annotation/` | 参数来源注解 |
| `SequentialOnly.java` | `agent/annotation/` | 顺序执行注解 |

### 8.2 公共模型

| 文件 | 路径 | 说明 |
|------|------|------|
| `ToolMetadata.java` | `agent/model/` | 工具元数据 |
| `ParameterInfo.java` | `agent/model/` | 参数信息 |

### 8.3 DAG 规划层（dag/plan/）

| 文件 | 路径 | 说明 |
|------|------|------|
| `GraphAnalyzer.java` | `agent/dag/plan/` | 依赖图分析器（原 DependencyGraphBuilder） |
| `PlanGenerator.java` | `agent/dag/plan/` | 执行计划生成器（原 DagPlanner） |
| `ExecutionPlan.java` | `agent/dag/plan/` | 执行计划 |
| `GraphBuildResult.java` | `agent/dag/plan/` | 依赖图构建结果（含未知工具） |

### 8.4 DAG 执行层（dag/executor/）

| 文件 | 路径 | 说明 |
|------|------|------|
| `PlanExecutor.java` | `agent/dag/executor/` | 执行器接口（原 DagExecutor） |
| `DefaultPlanExecutor.java` | `agent/dag/executor/` | 默认实现（原 DefaultDagExecutor） |
| `ToolResultStore.java` | `agent/dag/executor/` | 结果存储接口 |
| `InMemoryToolResultStore.java` | `agent/dag/executor/` | 内存实现（synchronized 锁） |
| `ToolInvoker.java` | `agent/dag/executor/` | 工具调用器接口 |
| `ToolResultEntry.java` | `agent/dag/executor/` | 工具结果条目（封装 result + type） |
| `DagExecutionResult.java` | `agent/dag/executor/` | 执行结果（含 metrics） |

### 8.5 策略（dag/strategy/，接口+实现扁平化）

| 文件 | 路径 | 说明 |
|------|------|------|
| `RetryStrategy.java` | `agent/dag/strategy/` | 重试策略接口 |
| `ExponentialBackoffRetryStrategy.java` | `agent/dag/strategy/` | 指数退避重试策略 |
| `NoRetryStrategy.java` | `agent/dag/strategy/` | 无重试策略 |
| `TimeoutStrategy.java` | `agent/dag/strategy/` | 超时策略接口 |
| `FutureTimeoutStrategy.java` | `agent/dag/strategy/` | Future 超时策略 |
| `NoTimeoutStrategy.java` | `agent/dag/strategy/` | 无超时策略 |
| `ToolResultCompressor.java` | `agent/dag/strategy/` | 结果压缩接口 |
| `TruncateCompressor.java` | `agent/dag/strategy/` | 截断压缩实现 |
| `LlmCompressor.java` | `agent/dag/strategy/` | LLM 压缩实现（预留） |

### 8.6 规划审查（dag/review/，扁平化）

| 文件 | 路径 | 说明 |
|------|------|------|
| `PlanReviewer.java` | `agent/dag/review/` | 规划审查接口 |
| `LlmPlanReviewer.java` | `agent/dag/review/` | LLM 审查实现（预留） |

### 8.7 执行指标（dag/metrics/）

| 文件 | 路径 | 说明 |
|------|------|------|
| `ToolExecutionMetrics.java` | `agent/dag/metrics/` | 执行指标 |

### 8.8 工具调用循环（agent/loop/）

| 文件 | 路径 | 说明 |
|------|------|------|
| `SubAgentToolLoop.java` | `agent/loop/` | 工具调用循环接口（已有） |
| `SerialToolLoop.java` | `agent/loop/` | 串行策略（已有） |
| `BatchToolLoop.java` | `agent/loop/` | 并行策略（已有） |
| `HybridToolLoop.java` | `agent/loop/` | 混合 DAG 策略（新增） |

### 8.9 配置（agent/config/）

| 文件 | 路径 | 说明 |
|------|------|------|
| `DagProperties.java` | `agent/config/` | DAG 配置 |
| `SubTaskProperties.java` | `agent/config/` | SubTask 配置属性（compressLength 等） |
| `ThreadPoolConfig.java` | `agent/config/` | 线程池配置 |

---

## 九、预期效果

假设每个工具平均耗时 3s，实际耗时服从正态分布（μ=3s, σ=1s）

| 场景 | 串行模式 | 混合 DAG 模式 | 提升幅度 |
|------|----------|---------------|----------|
| **3 个无依赖工具** | ~9s | ~3~5s（取决于最慢工具） | 40%~67% |
| **3 个有依赖工具** | ~9s | ~9s（必须串行） | 0% |
| **混合场景（2并行+1串行）** | ~9s | ~6s（2并行3s+串行3s） | 33% |
| **复杂 DAG（4工具，2层）** | ~12s | ~6s（层1并行3s+层2串行3s） | 50% |

> 注：实际提升取决于工具执行时间的方差和依赖关系复杂度

### 性能保障

| 机制 | 说明 |
|------|------|
| **层级超时** | 30s，防止整层卡死 |
| **单工具超时** | 默认 10s，支持按工具名配置（如网络请求 15s、计算 5s） |
| **错误重试** | 3次，指数退避，处理临时性错误 |
| **降级兜底** | DAG 失败时自动降级到串行执行 |

---

## 十、后续优化

| 优化项 | 说明 | 优先级 |
|--------|------|--------|
| **审查 LLM** | 启用 LLM 审查规划合理性 | P3 |
| **动态依赖** | 运行时动态添加依赖关系 | P3 |
| **结果缓存** | 相同参数的工具结果缓存 | P2 |
| **可视化监控** | DAG 执行过程可视化 | P3 |
| **分布式执行器** | 支持跨节点执行 DAG | P3 |
| **工具调用统计** | 按工具名统计调用次数、成功率、平均耗时 | P2 |
| **依赖图可视化** | 生成 Mermaid/PlantUML 依赖图 | P3 |

---

*文档结束*
