---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/guard/GuardedToolCallback.java
  - picknear/src/main/java/com/hmdp/agent/guard/ToolGuardGate.java
  - picknear/src/main/java/com/hmdp/agent/guard/ToolGuardManager.java
  - picknear/src/main/java/com/hmdp/agent/guard/policy/HighRiskListPolicy.java
  - picknear/src/main/java/com/hmdp/agent/guard/policy/ConfirmToolPolicy.java
  - picknear/src/main/java/com/hmdp/agent/guard/policy/PatternMatchPolicy.java
  - picknear/src/main/java/com/hmdp/agent/guard/policy/RateLimitPolicy.java
  - picknear/src/main/java/com/hmdp/agent/aspect/ToolPermissionAspect.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/confirm/ConfirmFlowManager.java
  - picknear/src/main/java/com/hmdp/agent/orchestration/confirm/ConfirmResumeService.java
  - picknear/src/main/resources/application.yaml
superseded_by:
---

# Agent 安全与审批设计

本文描述工具调用的 Guard、数据权限和 CONFIRM 审批链路。反编造与事实账本见
[Agent 诚实机制设计](Agent诚实机制设计.md)。

## 1. 安全边界

工具安全分为三层：

```text
ToolGuardManager
  -> ToolPermissionAspect
  -> CONFIRM 审批恢复
```

| 层 | 拦截依据 | 执行位置 |
|---|---|---|
| Guard | 工具名、参数、频率 | `ToolCallback.call()` 前 |
| 数据权限 | 用户 ID、目标资源 ID、操作类型 | `@Tool` 方法执行前 |
| CONFIRM | Guard 投票要求人工确认 | 首次调用暂停，审批后续跑 |

## 2. Guard 链路

工具统一包装为 `GuardedToolCallback`：

```text
GuardedToolCallback
  -> ToolGuardGate
  -> ToolGuardManager
  -> List<ToolGuardPolicy>
```

`ToolGuardGate` 负责：

1. 调用 `ToolGuardManager.evaluate()`；
2. 创建 guard span；
3. 按最终决策执行 `BLOCK`、`CONFIRM` 或 `ALLOW`。

## 3. 决策规则

`ToolGuardManager` 汇总所有策略投票：

```text
任一 BLOCK       -> BLOCK
无 BLOCK 且有 CONFIRM -> CONFIRM
其余             -> ALLOW
```

策略异常会被捕获并跳过。Redis 限流异常按 fail-open 放行，避免基础设施故障阻断工具主链。

## 4. 当前策略

| 策略 | 输入 | 行为 |
|---|---|---|
| `HighRiskListPolicy` | `block-tools` 精确名单 | 命中返回 `BLOCK` |
| `ConfirmToolPolicy` | `confirm-tools` 精确名单 | 命中返回 `CONFIRM` |
| `PatternMatchPolicy` | 工具名或参数正则 | 命中拦截或确认规则 |
| `RateLimitPolicy` | 会话级 Redis 计数 | 超过窗口阈值返回 `BLOCK` |

配置都位于 `hmdp.prompt-guard`。

## 5. 数据权限

### 5.1 声明

工具方法使用：

```java
@RequiredDataPermission(resource = "blog", action = DataAction.UPDATE)
```

### 5.2 切面流程

`ToolPermissionAspect` 执行：

1. 从方法参数中读取 `ToolContext`；
2. 从 `ToolContext` 读取 `userId`；
3. 自动读取第一个 `Long` / `Integer` 参数作为目标资源 ID；
4. 根据 `resource` 从 `PermissionValidatorFactory` 获取校验器；
5. 调用校验器；
6. 失败返回与 Guard 一致的错误 JSON。

没有目标资源 ID 时，当前实现直接放行。该规则用于“查询自己全部数据”一类自限查询，也意味着新增写操作时必须确认参数中确实带目标 ID。

## 6. CONFIRM 暂停

### 6.1 触发

`ToolGuardGate` 得到 `CONFIRM` 且审批开启时，抛出
`ConfirmRequiredException`。

`MultiRoundOrchestrator` 捕获后调用 `ConfirmFlowManager.pause()`。

### 6.2 快照

暂停快照包含：

- 原始输入；
- 当前中间回复；
- 已完成工具；
- 当前轮次；
- 待执行工具名和参数；
- 会话、用户和根 span。

审批记录通过 `ApprovalService` 持久化，同时发送 `confirm` SSE 事件。

## 7. 审批与恢复

### 7.1 状态变更

`ApprovalService` 使用 CAS 将 pending 记录改为 approved 或 rejected，防止重复确认和并发操作。

### 7.2 恢复装配

`ConfirmResumeService`：

1. 创建新的 SSE 会话和根 span；
2. 从审批记录恢复 `TaskSnapshot`；
3. 重建 `AgentContext`；
4. 委托 `TaskPlanner.resume()`。

### 7.3 已批准工具执行

`ConfirmFlowManager.executeApprovedTool()`：

- 按工具名找到 `GuardedToolCallback`；
- 使用 `callBypass()` 跳过重复 Guard 投票；
- 仍通过 `ToolCallExecutor` 执行参数解析、权限切面和结果限长；
- 将结果写入新的任务历史；
- 重新进入多轮编排。

跳过的是“同一调用再次投票”，不是权限和工具执行本身。

## 8. SSE 与状态

暂停时发送 `type=confirm`，包含：

- `confirmId`
- 工具名
- 原因
- 参数

恢复时继续发送 `step` 状态，并最终发送回答或错误。

## 9. 扩展约束

1. 新增 Guard 策略实现 `ToolGuardPolicy` 并注册为 Spring Bean。
2. 新增数据资源实现 `DataPermissionValidator`，不需要修改切面。
3. 新增写工具时，优先使用精确 `confirm-tools`，正则规则只用作兜底。
4. 不允许在业务工具中绕过 `GuardedToolCallback` 直接暴露给模型。
5. 审批恢复必须保持用户、会话、根 span 和参数快照完整。

## 10. 关联文档

- 执行链见 [Agent 执行链设计](Agent执行链设计.md)。
- 配置入口见 [快速开始与配置](runbook/快速开始与配置.md)。
