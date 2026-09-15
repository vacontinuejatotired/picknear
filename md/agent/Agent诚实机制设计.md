---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/honesty/DataIntentPromptHook.java
  - picknear/src/main/java/com/hmdp/agent/honesty/DataIntentClassifier.java
  - picknear/src/main/java/com/hmdp/agent/honesty/DataAssertionHook.java
  - picknear/src/main/java/com/hmdp/agent/honesty/DataIntentEmptyPlanFallback.java
  - picknear/src/main/java/com/hmdp/agent/execution/evidence/DefaultToolResultCapture.java
  - picknear/src/main/java/com/hmdp/agent/honesty/gate/EvidenceAssertionGate.java
  - picknear/src/main/java/com/hmdp/agent/honesty/gate/NumericClaimExtractor.java
  - picknear/src/main/java/com/hmdp/agent/history/ledger/RedisFactLedgerStore.java
  - picknear/src/main/java/com/hmdp/agent/history/ledger/LedgerLineComposer.java
  - picknear/src/main/java/com/hmdp/agent/history/ConversationReplayServiceImpl.java
superseded_by:
---

# Agent 诚实机制设计

本文描述数据意图、工具证据、输出断言和事实账本。Guard、权限和审批见
[Agent 安全与审批设计](Agent安全与审批设计.md)。

## 1. 目标

诚实机制解决三个问题：

1. Phase 1 在无工具状态下可能直接回答数据问题；
2. 子 Agent 摘要可能包含没有工具证据的统计断言；
3. 普通历史原文可能被下一轮误当成已核实事实。

当前实现为 P0 基线，采用“确定性规则 + Fail-Open”，先保证证据链和可观测性，不依赖额外模型裁判。

## 2. 分层

| 层 | 作用 | 当前实现 |
|---|---|---|
| L0 | 捕获工具真值 | `DefaultToolResultCapture` |
| L1 | 输入侧强制数据问题规划 | 数据意图分类、Prompt Hook、AfterAi Hook、空计划兜底 |
| L3 | 子 Agent 摘要断言检查 | `EvidenceAssertionGate` |
| L4 | 事实账本与回放区隔 | `RedisFactLedgerStore` |

## 3. L0 工具证据

### 3.1 捕获时机

`ToolExecutionFacade.execute()` 每轮开始时调用：

```text
toolResultCapture.begin()
```

工具循环在工具成功返回后登记：

```text
toolResultCapture.capture(toolName, raw)
```

### 3.2 状态载体

证据累加器存放在当前请求的 `AgentContext.attributes`：

- `AgentContext` 已跨线程传播；
- attributes 是 `ConcurrentHashMap`；
- 累加器使用同步 List，支持并行工具；
- 每轮 `begin()` 覆盖旧列表，避免跨轮残留。

### 3.3 快照

一轮结束后 `snapshot()`：

- 取出并移除本轮证据；
- 防止下一轮继续携带旧证据；
- 把证据挂到 `ExecutionOutput.toolEvidence`。

证据捕获是旁路增强。上下文缺失或捕获异常不会阻断工具执行。

## 4. L1 输入侧

### 4.1 数据意图分类

`DataIntentClassifier` 使用保守关键词规则识别：

- 平台统计；
- 我的数据；
- 天气；
- 店铺；
- 用户；
- 优惠券；
- 博客；
- 时间。

`DataIntentPromptHook` 在输入 Hook 链中打标，不修改用户输入。

### 4.2 强制规划

`DataAssertionHook` 若发现数据意图标记，直接返回 `PLANNING`：

```text
数据问题
  -> Phase 1 可能已经生成未经核实的文本
  -> AfterAiHook 强制转入 Phase 2
```

同时写入中性 `PLAN_SEED_TEXT`，避免 Phase 1 的推测数字污染规划上下文。

### 4.3 空计划兜底

数据意图请求进入规划后若得到空计划：

```text
DataIntentEmptyPlanFallback
  -> "抱歉，我暂时没有查询到相关数据..."
```

不得把 Phase 1 文本当作最终数据答案。

## 5. L3 输出断言闸

### 5.1 Detector A

`NumericClaimExtractor` 识别：

```text
共 / 共计 / 一共 / 当前共有 N 篇|家|位|条|个...
```

若摘要存在统计断言，但本轮证据没有任何统计工具：

```text
queryTotalBlogs
queryTotalShops
queryTotalUsers
```

则 `EvidenceAssertionGate` 命中。

### 5.2 处置档位

| action | 当前行为 |
|---|---|
| `OFF` | 关闭 |
| `OBSERVE` | 默认，只记录日志 |
| `APPEND_DISCLAIMER` | 追加“未核实”说明 |
| `RECHECK` | P1 预留，目前按放行处理 |
| `DROP` | P1 预留，目前按放行处理 |

断言闸异常时原样返回 summary，不阻断主链。

## 6. L4 事实账本

### 6.1 写入

`RoundExecutionProxy` 在每轮成功后：

```text
toolEvidence
  -> LedgerLineComposer
  -> Redis List `agent:conv:{cid}:ledger`
```

一行只保存工具名和工具真值摘要：

- 多行文本折叠为单行；
- 单行上限 200 字符；
- Redis List 追加避免读改写竞态；
- 默认 TTL 7 天；
- 读取预算默认 2000 字符，保留最新行。

账本只保存工具真值，不保存模型转写、总结或推测。

### 6.2 回放

`ConversationReplayServiceImpl` 在读取历史时：

```text
【已核实事实】工具原始结果
  -> 可选

【历史原文】仅供上下文，不代表已核实事实
  -> 最近完整消息
```

事实区和普通历史区必须分开，避免模型把历史对话中的数字当成已验证数据。

## 7. Fail-Open 边界

以下路径均不阻断主对话：

- 数据意图分类异常；
- 证据捕获异常；
- 断言闸异常；
- 事实账本 Redis 读写异常；
- 事实账本超预算。

Fail-Open 保证可用性，但会降低保护强度。相关异常必须保留 WARN 日志。

## 8. 当前限制

1. L1 使用关键词分类，覆盖范围取决于词表。
2. L3 只实现窄规则 Detector A，主要覆盖平台统计数字。
3. `RECHECK` 和 `DROP` 尚未实现。
4. L4 只记录工具真值，不提供跨会话事实推理。
5. 事实账本容量有限，读取时只保留最新预算内的行。

## 9. 扩展约束

1. 新检测器必须返回可解释的 `Claim`。
2. 新处置档位必须先校准命中率，再改变用户可见输出。
3. 事实账本不得写入模型生成内容。
4. 修正回放顺序时，必须保持“事实”和“历史原文”区隔。
5. 新增诚实机制不能破坏主链 Fail-Open。

## 10. 关联文档

- 回放和压缩见 [Agent 上下文与记忆设计](Agent上下文与记忆设计.md)。
- 工具执行见 [Agent 执行链设计](Agent执行链设计.md)。
