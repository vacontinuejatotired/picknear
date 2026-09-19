---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/controller/ChatController.java
  - picknear/src/main/java/com/hmdp/agent/access/AgentRuntime.java
  - picknear/src/main/java/com/hmdp/agent/access/AgentAccessService.java
  - picknear/src/main/java/com/hmdp/agent/access/AgentV2Controller.java
  - picknear/src/main/java/com/hmdp/agent/runtime/legacy/LegacyAgentRuntime.java
  - picknear/src/main/java/com/hmdp/agent/runtime/graph/AgentGraphFactory.java
  - picknear/src/main/java/com/hmdp/agent/runtime/graph/GraphAgentRuntime.java
  - picknear/src/main/java/com/hmdp/agent/service/impl/AiServiceImpl.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/TaskPlanner.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/MultiRoundOrchestrator.java
  - picknear/src/main/java/com/hmdp/agent/plan/TreePlanRouter.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/round/RoundExecutionProxy.java
  - picknear/src/main/java/com/hmdp/agent/execution/ToolExecutionFacade.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/confirm/ConfirmFlowManager.java
  - picknear/src/main/java/com/hmdp/agent/history/ConversationReplayServiceImpl.java
  - picknear/src/main/java/com/hmdp/agent/observability/api/AgentTracer.java
superseded_by:
---

# Agent 模块架构设计

本文描述 Agent 模块当前生效的主链、组件职责和扩展边界。实现细节以 `source_of_truth` 中的代码为准。

## 1. 设计边界

1. 对话入口只使用 SSE，JSON 同步对话模式已经删除。
2. Phase 1 不绑定工具，只做文本回复和决策输入。
3. 需要工具时由 Phase 2 统一规划、执行、汇总和落库。
4. 工具调用必须经过 Guard 和权限检查；需要人工操作时进入 CONFIRM 暂停。
5. 多轮记忆使用数据库历史、Redis 记忆视图和事实账本，不依赖 JDBC ChatMemory 作为运行历史源。
6. 文档、配置和代码冲突时，以代码、配置、运行时行为和可执行测试为准。

代码中仍有少量旧的“双模”注释，这是注释迁移债，不代表当前接口行为。

## 2. 运行入口

### 2.1 ChatController

`ChatController` 暴露：

| 接口 | 作用 |
|---|---|
| `POST /agent/string/send` | 发起一轮 SSE 对话 |
| `POST /agent/confirm` | 审批通过并恢复暂停的执行 |
| `POST /agent/reject` | 拒绝待审批工具 |

`/agent/string/send` 的核心入口步骤如下：

1. 生成或复用 `conversationId`。
2. 通过 `SseSessionFactory` 创建会话根 span 和 `ObservedSseEmitter`。
3. 构建请求级 `AgentContext`。
4. 推送 `conversationId` 的 `meta` 事件。
5. 调用 `AiService.chatWithToolcall`。

### 2.2 SseSessionFactory

`SseSessionFactory` 统一完成：

- 创建 `agent.session` 根 span；
- 构造带生命周期收敛的 `ObservedSseEmitter`；
- 推送 `meta` 元事件。

SSE 的完成、异常、超时和兜底 TTL 最终都收敛到根 span 结束。

### 2.3 Agent V2 接入层

`AgentV2Controller` 提供 `/agent/v2/string/send`，只负责接收参数并委托
`AgentAccessService`。接入服务构造请求模型后，通过 `AgentRuntime` 接口调用当前
运行时；默认实现为 `LegacyAgentRuntime`，用于把请求转交现有 `AiService` 链路。

旧 `/agent/string/send` 也复用同一接入服务，当前不会改变既有 SSE 协议。

当配置 `agent.access.runtime=graph` 时，接入层切换到 `GraphAgentRuntime`。当前
Graph 实现已接入最小 Phase1：组装系统提示、历史消息和当前输入，经 Graph 节点
调用 ChatModel，并由 Graph 的 `StreamingOutput` 逐段推送 SSE。当前不包含工具、
规划和审批；这些能力仍待后续接入。

## 3. Phase 1：输入决策与文本回复

### 3.1 PromptHookExecutor

`PromptHookExecutor` 执行输入侧 Hook 链，产出 `HookOutcome`：

| 决策 | 行为 |
|---|---|
| `BLOCK` | 结束请求并推送错误 |
| `REPLACE` | 使用替换后的输入继续 |
| `PASS` | 使用原始输入继续 |

当前数据意图打标 Hook 会写入 `AgentContext.attributes`，供后续规划决策使用。

### 3.2 StreamingChatInvoker

`StreamingChatInvoker` 负责：

- 接收 `AiServiceImpl` 预先读取的近期历史；
- 使用 `Phase1PromptAssembler` 组装系统提示、历史和当前用户消息；
- 直接调用 `ChatModel.stream()`；
- 将 token 持续推送到 SSE；
- 最多重试 3 次，429 使用更长退避；
- 在重试时只追加错误提示，不改变已构建的历史窗口。

### 3.3 SseResponseProcessor

完整回复生成后，`SseResponseProcessor` 负责：

1. 执行 `AfterAiHookChain`；
2. 执行 `PASS` 或 `REPLACE` 的历史落库；
3. 调用 `AiResponseRouter` 分发后续处理。

`AfterAiHookChain` 的优先级为：

```text
BLOCK > REPLACE > PLANNING > PASS
```

数据意图 Hook 会强制 `PLANNING`，避免未经工具核实的 Phase 1 数据回答成为最终答案。

### 3.4 AiResponseRouter

`AiResponseRouter` 根据决策分发：

| 决策 | 后续 |
|---|---|
| `BLOCK` | 推送错误并结束 |
| `REPLACE` | 推送替换文本并结束 |
| `PLANNING` | 提交 `TaskPlanner` |
| `PASS` | 保持已经流式输出的回复并结束 |

进入规划前，数据类请求可以使用中性 seed 覆盖 Phase 1 文本，避免未经核实的数据污染规划上下文。

## 4. Phase 2：规划与编排

### 4.1 TaskPlanner

`TaskPlanner` 是异步编排门面，负责：

- 在 `subtaskExecutor` 中执行规划链；
- 重新挂载根 span；
- 调用 `MultiRoundOrchestrator`；
- 完成最终回复和历史落库；
- 处理 CONFIRM 暂停后的恢复入口。

### 4.2 MultiRoundOrchestrator

`MultiRoundOrchestrator` 负责主循环，最多执行 5 轮：

```text
规划一轮
  -> 空计划：结束或诚实兜底
  -> 有任务：推送 plan 事件
  -> 子 Agent 路径 / 回退路径执行
  -> 更新当前回复
  -> 下一轮
```

每轮规划都会创建 `agent.plan` 观测；每轮执行都会创建 `agent.round` 观测。

当工具守卫抛出 `ConfirmRequiredException` 时，主循环停止并交给 `ConfirmFlowManager` 保存快照。

### 4.3 PlanRouter

`PlanRouter` 是规划策略接口：

| 实现 | 激活条件 | 行为 |
|---|---|---|
| `TreePlanRouter` | `feature.tool-routing.enabled=true`，默认 | 意图树剪枝 + 两段式规划 |
| `LegacyPlanRouter` | `feature.tool-routing.enabled=false` | 紧凑目录，不确定时使用全量目录重试 |

规划流程通常包括：

1. 尝试从 Phase 1 回复中解析已有计划；
2. 构建可用工具目录；
3. 调用规划模型；
4. 解析并校验工具、参数和依赖；
5. 输出 `SubTask` 列表。

## 5. 工具执行

### 5.1 默认子 Agent 路径

`feature.subagent.enabled=true` 时，`RoundExecutionProxy` 负责一轮执行：

1. 将任务、当前回复和历史摘要转换为 `ExecutionInput`；
2. 通过 `SseSubAgentCallback` 推送任务状态；
3. 调用 `ToolExecutionFacade`；
4. 经过证据断言闸；
5. 记录本轮历史和事实账本。

`ToolExecutionFacade` 负责：

- 筛选当前轮允许使用的工具；
- 构建系统提示和执行提示；
- 调用 `RetryRunner`；
- 解析工具结果和最终摘要；
- 收集本轮工具证据；
- 为未调用任务补发 `SKIPPED` 状态。

工具循环策略由 `agent.subtask.tool-loop` 选择：

| 策略 | 行为 |
|---|---|
| `serial` | 按轮串行执行 |
| `batch` | 可在同一轮批量执行并并行压缩 |
| `hybrid` | 根据依赖分层，同层并行、跨层串行；当前默认 |

### 5.2 回退路径

`feature.subagent.enabled=false` 时，`FallbackRoundExecutor` 使用 `TaskQueue` 和 `TaskExecutor` 串行执行，再调用合并逻辑。

该路径是兼容回退，不是当前默认主链。

### 5.3 Guard 与权限

完整安全和审批边界见 [Agent 安全与审批设计](Agent安全与审批设计.md)。

工具统一包装为 `GuardedToolCallback`：

```text
GuardedToolCallback
  -> ToolGuardGate
  -> ToolGuardManager
  -> ToolGuardPolicy 投票
```

最终决策只有：

| 决策 | 行为 |
|---|---|
| `ALLOW` | 执行工具 |
| `BLOCK` | 返回拦截结果 |
| `CONFIRM` | 暂停并创建审批记录 |

权限校验由工具方法上的 `@RequiredDataPermission` 和数据权限切面执行。

## 6. CONFIRM 暂停与恢复

### 6.1 暂停

`ConfirmFlowManager.pause` 会保存：

- 原始输入；
- 当前中间回复；
- 已完成工具；
- 当前轮次；
- 待执行工具名和参数；
- 会话、用户和根 span 快照。

审批服务创建记录并发送 `confirm` SSE 事件。

### 6.2 恢复

用户调用 `/agent/confirm` 后：

1. `ApprovalService` 使用 CAS 将记录改为已批准；
2. `ConfirmResumeService` 创建新的 SSE 会话；
3. 从审批记录恢复 `TaskSnapshot` 和 `AgentContext`；
4. `TaskPlanner.resume` 执行已批准工具；
5. 已完成工具写入历史种子；
6. `MultiRoundOrchestrator` 继续规划，直到产生最终答案。

审批恢复直接调用已批准工具，不再重复执行同一轮 Guard 投票。

## 7. 上下文、记忆与诚实机制

上下文传播、历史回放和异步压缩的详细设计见
[Agent 上下文与记忆设计](Agent上下文与记忆设计.md)。

### 7.1 AgentContext

`AgentContext` 通过 `ThreadLocal` 和线程池传播器跨异步边界传递：

- `userId`
- `conversationId`
- `originalInput`
- `rootSpan`
- 请求级 attributes

### 7.2 多轮回放

正常运行历史由 `ConversationReplayServiceImpl` 提供：

```text
可选历史摘要
  + 可选已核实事实
  + 压缩游标之后的近期完整消息
  -> Phase 1 Prompt
```

历史读取和 Redis 访问均为 fail-open，异常不会阻断主对话。

### 7.3 异步压缩

长会话由 `CompressionOrchestrator` 异步处理：

1. 读取 Redis `Mem`；
2. 探测待压缩消息；
3. 选择批次；
4. 生成摘要并执行关键数据保真；
5. 原子更新摘要和 `uptoId`；
6. 失败时保留原值并等待下一次写回合重试。

数据库中的 `agent_message` 始终是原始历史事实源。

### 7.4 反编造链路

完整诚实机制见 [Agent 诚实机制设计](Agent诚实机制设计.md)。

当前机制分为：

| 层 | 作用 |
|---|---|
| 输入侧 | 规则识别数据意图并强制规划 |
| 空计划 | 数据问题拿不到工具计划时返回诚实兜底 |
| 证据捕获 | 工具执行时记录真值证据 |
| 输出断言闸 | 在子 Agent 摘要进入后续链路前检查断言 |
| 事实账本 | 保存工具真值，并在后续请求中区隔事实与普通历史 |

## 8. 可观测性

完整观测设计见 [Agent 观测设计](Agent观测设计.md)。

业务埋点唯一入口是 `AgentTracer`，主链不直接依赖 Micrometer 或具体观测后端。

观测层级包括：

```text
agent.session
  -> agent.prompt_hook
  -> agent.phase1
  -> agent.decision
  -> agent.round
     -> agent.plan
     -> agent.subagent
        -> agent.guard
        -> tool_call
```

后端由 `hmdp.ai-observability.backend.type` 选择：

`langfuse`、`jaeger`、`signoz`、`collector`、`console`、`noop`

跨线程时通过根 span 重新 `openScope`；`ObservedSseEmitter` 负责所有结束路径收敛。

## 9. 扩展点

| 扩展目标 | 落点 |
|---|---|
| 新增工具 | `@TargetTool` + `@Tool` + `@ToolMeta` |
| 新增 Guard 策略 | 实现 `ToolGuardPolicy` |
| 新增输入 Hook | 实现 `PromptHook` |
| 新增输出决策 Hook | 实现 `AfterAiHook` |
| 新增规划策略 | 实现 `PlanRouter` |
| 新增工具循环策略 | 实现 `ToolExecutionStrategy` |
| 新增观测后端 | 实现 `TraceBackend` 并补充配置 |

## 10. 文档边界

- 启动和配置见 [快速开始与配置](runbook/快速开始与配置.md)。
- 规划、工具循环和 DAG 细节见 [Agent 执行链设计](Agent执行链设计.md)。
- SSE 事件格式、观测排障和评测将在后续迁移中合并到各自当前文档。
- 旧版 `Agent模块处理流程.md` 已删除，历史由 Git 保存。
