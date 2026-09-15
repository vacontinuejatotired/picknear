---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/context/AgentContext.java
  - picknear/src/main/java/com/hmdp/agent/context/AgentContextHolder.java
  - picknear/src/main/java/com/hmdp/agent/context/AgentContextPropagator.java
  - picknear/src/main/java/com/hmdp/agent/history/ConversationReplayServiceImpl.java
  - picknear/src/main/java/com/hmdp/agent/history/ReplayBudgetTrim.java
  - picknear/src/main/java/com/hmdp/agent/history/compression/CompressionOrchestrator.java
  - picknear/src/main/java/com/hmdp/agent/history/compression/TailBatchSelector.java
  - picknear/src/main/java/com/hmdp/agent/history/compression/LlmConversationSummarizer.java
  - picknear/src/main/java/com/hmdp/agent/history/fidelity/FidelityAssurance.java
  - picknear/src/main/resources/application.yaml
superseded_by:
---

# Agent 上下文与记忆设计

本文描述请求级上下文、历史落库、多轮回放和异步压缩。事实账本会在后续“诚实机制”主题中继续展开，这里只说明它在回放中的注入位置。

## 1. 上下文边界

Agent 链路中存在四类上下文，不能混用：

| 类型 | 生命周期 | 载体 |
|---|---|---|
| 请求级上下文 | 一次请求 | `AgentContext` + `AgentContextHolder` |
| 跨请求审批上下文 | 暂停到恢复 | `TaskSnapshot` + `AgentApproval` |
| 任务级上下文 | 一轮规划/执行 | `ExecutionInput`、`ExecutionSession` |
| Web 认证上下文 | HTTP 请求线程 | `UserHolder` |

### 1.1 AgentContext

`AgentContext` 当前包含：

- `userId`
- `conversationId`
- `originalInput`
- `history`
- `rootSpan`
- `attributes`

主字段不可变；阶段标记、数据意图和暂停快照等扩展信息写入线程安全的 `attributes`。

`AgentContext.history` 是历史兼容字段。当前正常对话的回放事实源是
`ConversationReplayServiceImpl`，不是该字段，也不是 JDBC ChatMemory。

### 1.2 ThreadLocal 与跨线程传播

请求入口创建一次 `AgentContext` 并写入 `AgentContextHolder`。

同步段直接读取；进入 `aiTaskExecutor` 或 `subtaskExecutor` 时，
`AgentContextPropagator` 作为 `TaskDecorator` 自动完成：

```text
提交线程捕获
  -> 执行线程恢复
  -> finally 清理
```

`require()` 在上下文缺失时直接抛异常，避免异步链路深处出现空指针。

## 2. 历史落库

### 2.1 数据表

| 表 | 用途 |
|---|---|
| `agent_conversation` | 会话元数据 |
| `agent_message` | 完整 user / assistant 原文 |

`agent_message` 是原始历史事实源，压缩不会删除其中任何消息。

### 2.2 落库时机

| 路径 | 记录时机 |
|---|---|
| Phase 1 `PASS` / `REPLACE` | `SseResponseProcessor` 在结束 SSE 前记录 |
| Phase 2 最终答复 | `TaskPlanner.completeTurn` 记录 |
| CONFIRM 暂停 | 暂停回合不落最终记录，恢复完成后记录 |
| BLOCK | 不落库 |

历史落库是 best-effort。数据库异常只记录日志，不阻断对话。

## 3. 多轮回放

### 3.1 配置

| 配置 | 默认值 | 作用 |
|---|---|---|
| `agent.replay.enabled` | `true` | 回放总开关 |
| `agent.replay.keep-recent-turns` | `6` | 最近完整轮数 |
| `agent.replay.max-replay-chars` | `3000` | 历史累计字符预算，`0` 表示不限制 |

### 3.2 读取流程

`ConversationReplayServiceImpl.recentMessages()`：

1. 读取 Redis `Mem`；
2. 查询 `(conversationId, userId)` 下的尾部消息；
3. 有压缩游标时，只读取 `id > uptoId` 的消息；
4. 使用 `ReplayBudgetTrim` 从最旧开始裁剪字符预算；
5. 在存在摘要时注入 `SystemMessage(【历史摘要】...)`；
6. 在事实账本非空时注入 `【已核实事实】`；
7. 有普通历史时注入“历史原文不代表已核实事实”的区隔说明；
8. 将 `user` / `assistant` 映射为 Spring AI 消息。

消息顺序始终为：

```text
历史摘要（可选）
  -> 已核实事实（可选）
  -> 历史原文区隔说明（可选）
  -> 最近完整 user / assistant 消息
  -> 当前 user
```

### 3.3 预算裁剪

`ReplayBudgetTrim`：

- 输入消息必须按 ID 升序；
- 从最旧开始丢弃；
- 最新一条始终保留，即使单条超过预算；
- `maxReplayChars <= 0` 时不裁剪。

### 3.4 Fail-Open

读取数据库或 Redis 失败时返回空历史并记录 WARN，不阻断当前对话。

## 4. 异步压缩

### 4.1 记忆视图

Redis 中每个会话只维护一个 `Mem`：

```text
summary   已压缩旧轮次的运行摘要
uptoId    已进入摘要的最后一条 agent_message.id
version   视图版本
updatedAt 更新时间
```

摘要和游标在同一 JSON 中原子写入，避免“游标前进但摘要没写入”的撕裂。

### 4.2 投递

回合落库事务提交后发送 `ConversationTurnRecordedEvent`：

```text
ConversationCompressionDispatcher
  -> Redisson 会话锁守卫
  -> compressExecutor
  -> CompressionOrchestrator
```

压缩线程池与流式、规划线程池隔离。队列拒绝时标记 dirty，由 sweeper 兜底。

### 4.3 选批

`TailBatchSelector`：

- 保留最近 `agent.replay.keep-recent-turns` 轮不压；
- token 估算超过阈值，或消息数达到阈值时触发；
- 最旧优先；
- 按 user / assistant 成对切批；
- 每批最多 `agent.context-compression.batch-turns` 轮；
- 批次摘要估算不超过 `summary.max-tokens`，至少保留一对。

`LoadProber` 一次最多读取 2000 条游标之后的消息，升序处理。

### 4.4 摘要与保真

`LlmConversationSummarizer` 使用独立 `compressChatClient`，默认模型为
`qwen-flash`。模型返回：

```json
{
  "summary": "运行摘要",
  "keyData": ["关键数据点"],
  "truncated": false
}
```

`FidelityAssurance` 从批次原文提取关键数据点，并与摘要和模型声明的
`keyData` 比对。数据点缺失且容量允许时，回填：

```text
【关键数据保留：...】
```

### 4.5 成功与失败

成功时：

1. 合并旧摘要和当前批次摘要；
2. 执行关键数据保真；
3. 原子更新 `summary + uptoId`；
4. 后续回放只读取新游标后的完整消息。

失败时：

- 不推进游标；
- 原始消息仍保留在 `agent_message`；
- 下一次写回合或 sweeper 可以再次触发；
- 压缩失败不影响当前对话返回。

## 5. 与 AgentContext 的关系

| 需求 | 使用的数据 |
|---|---|
| 请求级用户、会话和根 span | `AgentContext` |
| 跨线程传播 | `AgentContextPropagator` |
| 当前对话历史 | `ConversationReplayServiceImpl` |
| 长会话上下文 | Redis `Mem.summary` + 游标后原文 |
| 工具事实引用 | 事实账本 |
| 审批恢复 | `TaskSnapshot` / `AgentApproval` |

## 6. 配置导航

| 配置组 | 职责 |
|---|---|
| `agent.replay` | 历史窗口、开关和字符预算 |
| `agent.context-compression` | 压缩触发、批次、摘要和 Redis TTL |
| `agent.compression-executor` | 压缩线程池、锁和追平策略 |
| `agent.compress-model` | 压缩模型和温度 |

完整默认值以 `application.yaml` 为准。

## 7. 边界与迁移

1. `AgentContext.history` 不承担当前回放职责。
2. 原始历史以 `agent_message` 为准，不能只保留摘要。
3. 压缩和回放均需 fail-open，不能阻断主对话。
4. 事实账本只保存工具真值，具体反编造逻辑在后续诚实主题展开。
5. 旧的上下文、优化、压缩设计和记忆回放交接稿均已由本文替代。
