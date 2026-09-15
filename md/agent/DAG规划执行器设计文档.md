---
status: legacy
superseded_by: md/agent/Agent执行链设计.md
---

# DAG 规划执行器设计说明

> 版本：v1.11（精简说明版）
> 更新日期：2026-09-06
> 状态：分层调度与参数注入均已实现

---

## 一、目标

在 Agent 子任务工具循环中支持混合执行：

- 无依赖工具同层并行，提高吞吐。
- 有依赖工具跨层串行，保证顺序。
- 上游工具结果按静态绑定规则自动注入下游参数。
- LLM 只负责选工具和 Agent 参数，依赖关系由代码声明，框架负责正确性。

`agent.subtask.tool-loop=hybrid` 时启用本路径；`serial`、`batch` 行为不受影响。

---

## 二、组件职责

| 组件 | 职责 |
|------|------|
| `GraphAnalyzer` | 启动时扫描 `@Tool` 方法，登记工具元数据；提供工具名 → 依赖列表、返回类型，供计划生成和绑定解析使用。不负责运行时绑定，也不缓存每轮参数绑定结果。 |
| `ToolParameterBindingPlanFactory` / `Resolver` / `Matcher` | 规划期按优先级解析“下游参数 ← 上游工具”绑定；产出可注入绑定或可读 Issue。 |
| `ExecutionPlan` | 不可变执行计划值对象：分层列表、依赖图、当轮参数绑定计划。 |
| `DagStrategy` | hybrid 轮次编排器：选工具 → 生成计划 → 构建 invoker → 执行 → 压缩组装 `ToolResponse`；计划无效时返回可读原因。 |
| `DagToolInvokerFactory` | 把每个 `ToolCall` 组装成带参数注入能力的 `ToolInvoker`，隔离 invoker 构建细节。 |
| `ToolCallArgumentInjector` | 执行前把上游原始结果合并进 arguments JSON，只处理明确绑定参数；解析/合并失败抛可读异常。 |
| `DefaultPlanExecutor` | 按层执行：同层并发、跨层串行、单工具失败隔离、层超时、单工具重试。 |
| `ToolResultStore` / `InMemoryToolResultStore` | 保存本轮工具原始结果；`getRawResult` 可跨层读取，支持 null 哨兵；typed 查询只搜索当前层。 |
| `ToolResultCompressor` / `RetryStrategy` / `TimeoutStrategy` | 工具结果压缩、重试和超时策略，由对应策略接口隔离，不在编排器里硬编码。 |

---

## 三、依赖与参数绑定

### 3.1 声明方式

```text
method downstreamA:
  @DependsOn(toolName = ["upstreamB", "upstreamC"])

method downstreamB(parameter value):
  parameter value: @FromTool("upstreamC")
```

- `@DependsOn`：声明工具级执行顺序。
- `@FromTool`：显式声明某个形参来自哪个上游工具。
- `@SequentialOnly`：禁止与其它工具并行，单独一层。
- 参数名稳定依赖 `-parameters` 编译选项，项目已开启。

### 3.2 参数匹配优先级

```text
for parameter in downstreamMethod.parameters:
  if parameter has @FromTool(source):
    source must be declared in @DependsOn
    source return type must be assignable to parameter type
    binding = source
  elif exactly one dependency return type is assignable to parameter type:
    binding = that dependency
  elif more than one dependency return type matches:
    issue = ambiguous candidates
    stop, do not fall through to name matching
  elif parameter.name equals a dependency tool name:
    binding = that dependency
  else:
    agent parameter, no injection
```

策略实现顺序固定为 `FromToolSourceMatcher → UniqueReturnTypeSourceMatcher → DependencyNameSourceMatcher`。

---

## 四、执行流程

### 4.1 生成计划

```text
function plan(selectedTools):
  graph, unknownTools = GraphAnalyzer.buildGraph(selectedTools)

  if unknownTools not empty:
    return invalid("存在未知工具")
  if hasCycle(graph):
    return invalid("检测到循环依赖")
  if any declared dependency is not selected:
    return invalid("依赖未被选中")

  bindingPlan = ToolParameterBindingPlanFactory.create(selectedTools, metadata)
  if bindingPlan has issues:
    return invalid(issues)

  layers = topologicalSort(graph)
  return ExecutionPlan(layers, graph, bindingPlan)
```

### 4.2 执行器

```text
function execute(plan, tools):
  for layer in plan.layers:
    reset current layer entries

    run layer tools in parallel:
      try:
        result = retryAndTimeout(invoker)
        results[tool] = result
        store(tool, result, returnType)
      catch exception:
        results[tool] = null
        failed[tool] = readable reason
        store(tool, null, returnType)
        continue sibling tasks

    wait whole layer

    if layer timeout:
      cancel unfinished tasks
      return timeout error
      // 不进入后续层

    clearCurrentLayer()
```

### 4.3 参数注入

```text
function invokeTool(toolCall, bindings, toolContext):
  args = parseJson(toolCall.arguments)

  for binding in bindings:
    raw = ToolResultStore.getRawResult(binding.sourceTool)

    if raw is absent/null:
      args[binding.parameterName] = null
    else:
      args[binding.parameterName] = parseJson(raw)
      // 字符串文本按普通 String 放入，避免二次 JSON 转义

  payload = serialize(args)
  return GuardedToolCallback.call(payload, toolContext)
```

注入点选在 `ToolCallback.call` 之前，Guard / 参数占位符解析 / 观测仍然覆盖最终 payload。

---

## 五、关键语义

1. 单工具失败：结果按 `null` 存储，同层其余工具继续；下一层仍执行，下游收到 JSON `null`。
2. 层超时：整轮提前返回，不执行后续层，不使用“null 继续”语义。
3. 注入值覆盖 LLM 提供的同名参数。
4. 参数歧义、`@FromTool` 未声明来源、类型不兼容等静态问题：计划无效，返回可读原因，不静默执行。
5. 上游结果不是合法 JSON：抛 `ToolCallArgumentInjectionException`，按工具失败处理，不把坏参数传给下游。
6. `ToolResultStore` 的 null 使用内部哨兵表示，避开并发容器不能存 null value 的限制。

---

## 六、与 Spring AI 的关系

Spring AI 1.1.2 的 `ToolCallback.call(payload, toolContext)` 只负责：

- 按形参名从 JSON 取参并转成 Java 类型。
- 返回值统一转成 JSON String。

它没有“上游工具结果自动注入下游参数”的框架能力，也没有把依赖结果注入 `ToolContext` 的通用机制，因此本文档采用“调用前合并 arguments JSON”的方案。

---

## 七、配置要点

```yaml
agent:
  subtask:
    tool-loop: hybrid
    dag:
      layer-timeout-seconds: 30
      tool-timeout-seconds: 10
      default-max-retries: 3
      default-retry-enabled: true
      retry:
        strategy: exponential
```

---

## 八、边界说明

- 本设计只影响 `hybrid`/DAG 路径；`serial`、`batch` 不参与计划与绑定流程。
- 当前真实工具中的 `@DependsOn` 大多只约束执行顺序，工具形参仍是 LLM 负责的 ID 等 Agent 参数，不属于返回对象注入，因此无需为真实工具改签名。
- 执行期不重复扫描注解；绑定计划在当轮计划阶段生成一次，随 `ExecutionPlan` 传递到执行期。
