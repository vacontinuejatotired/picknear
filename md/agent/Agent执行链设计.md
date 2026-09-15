---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/orchestration/MultiRoundOrchestrator.java
  - picknear/src/main/java/com/hmdp/agent/plan/PlanRouter.java
  - picknear/src/main/java/com/hmdp/agent/plan/TreePlanRouter.java
  - picknear/src/main/java/com/hmdp/agent/legacy/plan/LegacyPlanRouter.java
  - picknear/src/main/java/com/hmdp/agent/plan/PlanSupport.java
  - picknear/src/main/java/com/hmdp/agent/plan/support/PlanValidator.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/round/RoundExecutionProxy.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/round/FallbackRoundExecutor.java
  - picknear/src/main/java/com/hmdp/agent/execution/ToolExecutionFacade.java
  - picknear/src/main/java/com/hmdp/agent/subagent/loop/AbstractToolLoop.java
  - picknear/src/main/java/com/hmdp/agent/execution/loop/strategy/SerialStrategy.java
  - picknear/src/main/java/com/hmdp/agent/execution/loop/strategy/ParallelStrategy.java
  - picknear/src/main/java/com/hmdp/agent/execution/loop/strategy/DagStrategy.java
  - picknear/src/main/java/com/hmdp/agent/plan/executionPlan/PlanGenerator.java
  - picknear/src/main/java/com/hmdp/agent/execution/loop/DefaultPlanExecutor.java
  - picknear/src/main/java/com/hmdp/agent/execution/loop/argument/ToolCallArgumentInjector.java
superseded_by:
---

# Agent 执行链设计

本文描述规划、工具循环和 DAG 的当前实现。组件关系以 [Agent 模块架构设计](Agent模块架构设计.md) 为准，本文只展开执行链。

## 1. 当前选择

| 配置 | 当前值 | 结果 |
|---|---|---|
| `feature.subagent.enabled` | `true` | 使用 `RoundExecutionProxy` 子 Agent 路径 |
| `feature.tool-routing.enabled` | `true` | 使用 `TreePlanRouter` |
| `agent.subtask.tool-loop` | `hybrid` | 使用 `DagStrategy` |

如果代码中缺少 `agent.subtask.tool-loop`，`SerialStrategy` 通过
`matchIfMissing=true` 兜底；但当前 `application.yaml` 明确配置为 `hybrid`。

历史名称对应关系：

| 旧名称 | 当前名称 |
|---|---|
| `SubTaskAgent` | 执行逻辑已拆分到 `ToolExecutionFacade` 和工具循环策略 |
| `SubTaskAgentRoundExecutor` | `RoundExecutionProxy` |
| `ToolCallLoopExecutor` | `AbstractToolLoop` 及三个策略实现 |
| `PlanLoopExecutor` | `MultiRoundOrchestrator` |

## 2. 规划入口

`MultiRoundOrchestrator` 每轮先调用 `PlanRouter.plan()`，最多执行 5 轮。

规划输入由 `PlanRequest` 携带：

- 当前用户输入；
- Phase 1 文本；
- 当前用户 ID；
- 全量工具回调；
- 已完成和历史任务。

`PlanOutcome.source()` 表示计划来源：

| source | 含义 |
|---|---|
| `from_response` | 直接从 Phase 1 回复中解析出计划 |
| `ai_plan` | 规划模型产出了计划 |
| `empty` | 没有可用计划 |

空计划默认结束当前轮。如果请求被标记为数据意图，则返回
`DataIntentEmptyPlanFallback` 的诚实兜底，禁止使用未经核实的 Phase 1 数据回答。

## 3. 规划策略

### 3.1 TreePlanRouter

默认策略。流程如下：

1. 使用 `ToolIntentTree` 从用户输入匹配意图节点；
2. 尝试从 Phase 1 回复直接解析计划，并执行相同树归属校验；
3. 按命中节点构建剪枝后的工具目录；
4. 目录为空时跳过规划模型调用，不回归全量工具；
5. 调用规划模型；
6. 解析计划并执行校验。

该路径通过树归属校验阻断“模型选择了未授权意图组中的工具”。

### 3.2 LegacyPlanRouter

仅当 `feature.tool-routing.enabled=false` 时启用：

1. Phase 1 直解不套意图树校验；
2. 使用紧凑工具目录；
3. 命中 `__UNCERTAIN__` 时使用全量目录重试一次；
4. 解析并执行 legacy 校验。

该路径是不可路由策略下的回退实现。

### 3.3 解析与校验

`PlanParser` 接受两类格式：

- 两级路由对象：`{"intents": [...], "plan": [...]}`；
- 旧数组格式：`[{"tool": "...", "params": {...}}]`。

解析优先读取 `===PLAN_START===` 和 `===PLAN_END===`，缺失标记时使用括号匹配提取
JSON。

`PlanValidator` 依次检查：

1. 工具名存在；
2. 工具未在历史中完成或最终失败；
3. 树路由开启时，工具属于声明意图或关键词命中节点；
4. `self` / `userId` 占位符解析为真实用户 ID。

非法单项会被丢弃并记录 WARN，不会因为一个错误条目拒绝整份计划。

## 4. 一轮执行

### 4.1 RoundExecutionProxy

默认子 Agent 路径的一轮执行由 `RoundExecutionProxy` 负责：

1. 将任务、当前回复和历史摘要组装为 `ExecutionInput`；
2. 创建带 SSE 回调的执行会话；
3. 调用 `ToolExecutionFacade.execute()`；
4. 对摘要执行证据断言闸；
5. 记录任务历史；
6. 将工具证据追加到事实账本。

### 4.2 ToolExecutionFacade

`ToolExecutionFacade` 是执行门面，负责：

- 开始本轮证据收集；
- 按计划工具名筛选 `ToolCallback`；
- 构建系统提示和执行提示；
- 通过 `RetryRunner` 执行当前策略；
- 解析 `===DATA_SNAPSHOT===` 数据快照；
- 对未实际调用的计划任务补发 `SKIPPED`；
- 将本轮证据快照挂到输出。

### 4.3 RetryRunner

`RetryRunner` 在工具循环外层执行重试：

- 遵守 `agent.subtask.total-timeout`；
- 最多重试 `agent.subtask.max-retries`；
- 普通错误使用指数退避；
- 429 使用更长的专用退避；
- `ConfirmRequiredException` 原样上抛，不重试；
- 重试时把错误原因追加到执行提示。

## 5. 工具循环策略

三种策略继承 `AbstractToolLoop`，共享以下骨架：

```text
调用模型
  -> 无工具调用：返回文本
  -> 有工具调用：执行本轮工具
  -> 压缩结果并更新历史摘要
  -> 移除已完成任务
  -> 检查总调用预算
  -> 下一轮
```

达到最大轮数或总调用数后，使用不带工具的模型调用强制总结。

### 5.1 SerialStrategy

`serial`：

- 提示词要求每轮只调用一个工具；
- 工具顺序执行；
- 每个结果单独压缩；
- 适合依赖关系不清晰或工具较少的场景。

### 5.2 ParallelStrategy

`batch`：

- 允许一轮返回多个独立工具调用；
- 工具调用阶段可并发；
- 结果压缩阶段可并发；
- 所有调用结束后统一写入工具响应和完成摘要；
- CONFIRM 信号在并发结果汇总后重新抛出。

### 5.3 DagStrategy

`hybrid`，当前默认：

1. 收集本轮模型选择的工具；
2. 可选执行 `PlanReviewer`；
3. 由 `PlanGenerator` 生成依赖分层计划；
4. 对每个工具构建带参数注入能力的 `ToolInvoker`；
5. 调用 `DefaultPlanExecutor`；
6. 将结果压缩后写回工具响应。

如果计划无效，当前实现会为相关工具返回可读错误，不会继续执行这些工具。
方法名虽然叫 `fallbackSerialExecution`，但当前行为不是重新串行执行，这是命名债。

## 6. DAG 计划与参数注入

### 6.1 依赖声明

| 注解 | 作用 |
|---|---|
| `@DependsOn` | 声明工具级依赖 |
| `@SequentialOnly` | 禁止与其它工具并行 |
| `@FromTool` | 声明参数来自哪个上游工具 |

`GraphAnalyzer` 启动时扫描 `@Tool` 方法，登记工具名、返回类型、依赖、幂等性和重试配置。

### 6.2 计划生成

`PlanGenerator` 依次检查：

1. 是否存在未知工具；
2. 是否存在循环依赖；
3. 依赖工具是否同时被选中；
4. 参数绑定是否存在歧义或类型错误；
5. 使用拓扑排序生成执行层。

参数绑定优先级：

```text
@FromTool
  -> 唯一可赋值的依赖返回类型
  -> 与依赖工具名相同的参数名
  -> 视为 Agent 参数，不注入
```

### 6.3 执行与注入

`DefaultPlanExecutor` 按层执行：

- 同层工具并行；
- 跨层串行；
- 单工具失败写入 `null`，同层其它工具继续；
- 后续层继续执行，下游从 `ToolResultStore` 读取 `null`；
- 层超时则取消未完成任务，并停止后续层。

`ToolCallArgumentInjector` 在调用 `ToolCallback.call()` 前，把上游原始结果合并进
arguments JSON。Guard、占位符解析和观测因此覆盖最终 payload。

## 7. 回退执行路径

`feature.subagent.enabled=false` 时，`FallbackRoundExecutor` 使用：

```text
TaskQueue
  -> TaskExecutor 串行执行
  -> 追加 LLM_REASON
  -> HistoryAggregator.merge
```

该路径用于兼容和排障，不包含当前默认的子 Agent 工具循环。

## 8. 状态与观测

SSE 事件格式见 [SSE 后端实现规范](SSE后端实现规范.md)，观测链路见
[Agent 观测设计](Agent观测设计.md)。

规划完成后发送 `plan` 事件，包含本轮任务清单。

工具执行期间发送 `step` 状态：

| 状态 | 含义 |
|---|---|
| `RUNNING` | 开始执行 |
| `COMPLETED` | 执行完成 |
| `FAILED` | 执行失败 |
| `SKIPPED` | 计划存在但模型没有调用 |

执行链观测包括：

```text
agent.round
  -> agent.plan
  -> agent.subagent
     -> agent.guard
     -> tool_call
```

## 9. 扩展约束

1. 新增工具必须声明 `@ToolMeta`，用于意图路由和元数据登记。
2. 有依赖的工具必须同时声明 `@DependsOn` 和必要的 `@FromTool`。
3. 新工具循环策略必须实现 `ToolExecutionStrategy`，并通过配置选择。
4. 不接受静默跳过参数绑定歧义；计划生成阶段必须返回可读错误。
5. 当前默认路径和配置值以 `application.yaml` 及源码为准。

## 10. 关联文档

- 整体组件关系见 [Agent 模块架构设计](Agent模块架构设计.md)。
- 启动、配置和验证见 [快速开始与配置](runbook/快速开始与配置.md)。
- Guard、权限和 CONFIRM 的完整安全边界在后续安全主题迁移中收敛。
