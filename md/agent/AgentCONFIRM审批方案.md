# Agent CONFIRM 审批方案 — 真暂停 + agent_approval 审批流

> **状态**: ✅ 已落地（2026-08-06）  
> **分支**: feature-confirm-approval  
> **关联**: [Agent模块发展路线图](./Agent模块发展路线图.md) §3.3.3 / §3.3.4 / §5.2；[Agent模块架构设计](./Agent模块架构设计.md) §3.10 守卫层  
> **范围**: 后端（本文档）+ 前端（`frontend/docs/AgentCONFIRM审批前端方案.md`）

---

## 1. 背景与目标

守卫层投出 CONFIRM（需用户确认）决策时，旧实现只把 `{"confirm":"..."}` 字符串当作工具结果喂回 LLM——工具既不执行也不暂停，无持久化、无超时、无审计（路线图 §3.2 P1 的三个问题：重复确认 / 无超时 / 无审计）。

**目标**：让 CONFIRM 成为真正的"审批门"——暂停规划 → 建审批记录 → 推确认事件 → 用户确认后恢复执行、拒绝/超时取消，全程可审计。

## 2. 现状问题（改造前）

| 问题 | 位置 | 后果 |
|------|------|------|
| CONFIRM 只返回字符串 | `GuardedToolCallback.call()` | 不真暂停，LLM 自说自话 |
| `hasConfirmTool()` 恒 false | `TaskPlanner` 死代码 | 快照/确认路径永不触发 |
| 无审批表/接口/超时 | — | 不可审计、永远挂起 |
| conversationId 冻结 | `GuardedToolCallback` 构造时 | 审批/限流记错会话 |
| `ChatContext.pendingSnapshot` 无人读写 | 逐请求新建的 ChatContext | 无跨请求恢复状态 |

## 3. 总体架构

```
用户输入 → [PromptHook] → [AI call 流式] → [AfterAiHook → PLANNING]
    → TaskPlanner 循环
        → 子 Agent/回退执行工具 → GuardedToolCallback
            ├─ ALLOW   → 执行工具
            ├─ BLOCK   → 返回错误字符串
            └─ CONFIRM → throw ConfirmRequiredException（携带 ToolInvocationContext）
        → TaskPlanner 专用 catch
            ① 构建 TaskSnapshot（含 pendingToolName/arguments/confirmId）
            ② ApprovalService.createApproval → agent_approval(pending, expired=now+ttl)
            ③ taskScheduler 排过期 + @Scheduled sweeper 兜底
            ④ 推结构化 type:confirm 事件（最后一个 data，随后 EOF）
            ⑤ 终止本轮流

用户确认  → POST /agent/confirm?confirmId=x（Accept: text/event-stream）
    → 原子 CAS pending→approved → 新 ObservedSseEmitter + resumeFromSnapshot
        → ① callBypass 执行待审批工具（守卫已批准，不再二次投票）
          ② 预置 history = completedTools ∪ {待审批工具}（全 COMPLETED）
          ③ 才进入正常规划循环（decompose 过滤已完成 → 防二次审批死循环）
用户拒绝  → POST /agent/reject?confirmId=x → pending→rejected（前端"操作已取消"）
5min 超时 → 懒过期 + sweeper → pending→expired（前端提示"已过期"）
```

### 异常路径（三轮审查定稿）

`ConfirmRequiredException` 是普通 `RuntimeException`。Spring AI `DefaultToolCallingManager` 只 catch `ToolExecutionException`，**自定义异常原样穿透**（字节码反编译证实，不包装）。真正吞异常的是：

- `SubTaskAgent.executeWithRetry` 的 `catch(Exception)` 重试循环（原实现吞掉并重试 3 次）
- `TaskExecutor.executeTool` 的 `catch(Exception)` markFailed

**修复**：两处 catch 最前加 `catch (ConfirmRequiredException e) { throw e; }` 立即 rethrow；`TaskPlanner.planAndExecute` round 循环外层加专用 `catch (ConfirmRequiredException)`（必须在 `planAndExecuteAsync` 通用兜底之前，否则转成"处理中断"硬流失败）。线程拓扑：catch 与子 Agent 同线程（subtaskExecutor），`SseEmitter.send` 线程安全。

## 4. 数据层 — `agent_approval`

DDL 见 `picknear/picknear/src/main/resources/db/heima-init.sql` 末尾（注意：**heima-init.sql 只对全新库生效，已初始化的开发库需手动执行同样 DDL**）。

| 列 | 说明 |
|----|------|
| `confirm_id` (UNIQUE) | `cfm_xxx`，全局唯一 |
| `conversation_id` / `user_id` | 归属（决策一律用记录里的 user_id，异步线程无 UserHolder） |
| `tool_name` / `tool_arguments`(JSON) | 待审批的工具调用 |
| `status` | pending / approved / rejected / expired |
| `expired_at` | 创建后 ttl（默认 300s） |
| `original_input` / `partial_response` / `completed_tools`(JSON) / `round` | resume 恢复快照用 |
| `executed_at` | 审批通过后实际执行时间，防重复执行 |

`ApprovalService`：`createApproval`（best-effort，DB 失败返回 null 降级内存快照）、`getByConfirmId`、`markApproved`（**原子 CAS**：`UPDATE ... WHERE confirm_id=? AND user_id=? AND status='pending' AND expired_at>=now`，校验影响行数防双击/清扫竞态）、`markRejected`、`markExpired`、`markExecuted`、`expireOverdue`（`@Scheduled` sweeper）。

## 5. 守卫层 — `GuardedToolCallback`

- CONFIRM 分支：`throw new ConfirmRequiredException(context, reason, policyName)`（`context` 为 `ToolInvocationContext`，携带 toolName/arguments/conversationId/userId）
- **conversationId 修复**：从 `toolContext.getContext()` 读取（原构造时冻结启动 UUID），子 Agent/回退 executor 显式注入真实会话
- `callBypass(functionPayload, toolContext)`：绕过守卫直调底层 delegate，仅审批恢复路径使用（已批准不再二次投票）
- 开关 `hmdp.prompt-guard.approval.enabled=false` 时退回旧行为（返回字符串给 LLM）
- `ToolBeanCollector.getToolCallback(name)`：按名取工具（resume 定位用）

## 6. TaskPlanner — 暂停 / 恢复

### 暂停（`handleConfirmPause`）
捕获异常后：建快照 → `createApproval` → 推 `type:confirm` 事件 → 返回 currentResponse。`completeTurn` 识别暂停态（`ctx.pendingSnapshot != null`）跳过尾文本推送与历史落库，保证确认事件是流中最后一个 data。

### 恢复（`resumeFromSnapshot`）— 顺序决定成败
1. **先用 `callBypass` 执行待审批工具**（ToolContext 显式塞 userId/conversationId；异步线程无 UserHolder，数据权限切面从 ToolContext 取 userId）
2. **再预置 history**：`completedTools ∪ {待审批工具}` 全部 COMPLETED（待审批工具从未完成，不显式加入会被重新规划 → 再次 CONFIRM → 无限循环）
3. **然后才 decompose**：`validatePlan` 按 `isCompleted` 过滤 → 不再进计划、不再触发守卫
4. `markExecuted` 记已执行；重建 ChatContext（userId/conversationId/originalInput 来自审批记录）保证历史落库与权限校验

## 7. 接口

| 端点 | 模式 | 行为 |
|------|------|------|
| `POST /agent/confirm` | 双模（Accept 协商） | 原子 CAS 通过审批；`Accept: text/event-stream` → 新 `ObservedSseEmitter` + `resumeFromSnapshot` 续流；否则 JSON 200 |
| `POST /agent/reject` | JSON | pending→rejected，返回 200 |

失败返回对应错误：越权→「审批记录不存在或无权操作」、已处理→「该审批已处理」、过期→「确认已过期，请重新发起」（前端据此提示）。

## 8. SSE 事件协议

```json
// 待审批（独立 type，最后一个 data，随后 EOF）
data: {"type":"confirm","confirmId":"cfm_xxx","tool":"publishTestBlog","reason":"⚠️ 需要确认：ConfirmToolPolicy","arguments":"{...}"}
```

前端 `readSSE` 新增 `onConfirm` 路由（主循环 + buffer 残留两处），confirm 事件不混入正文。原 `progress.stage=confirm` 单按钮路径移除（前端迁到审批卡片）。

## 9. 前端交互（详见 `frontend/docs/AgentCONFIRM审批前端方案.md`）

- 助手消息挂 `pendingConfirm` → 弹审批卡片（确认执行 / 拒绝）
- 确认 → `confirmContinue`（SSE，无 30s 超时）→ 前缀拼接接入同一消息（防覆盖确认前文本）
- 拒绝 → toast「操作已取消」；过期(400/410) → toast「确认已过期，请重新发起」
- `loading` 守门：审批挂起时锁输入，直到确认/拒绝 resolve；`activeMsg` 悬挂引用守卫（导航/切换会话失效则不追加）

## 10. 配置

```yaml
hmdp:
  prompt-guard:
    confirm-tools: [publishTestBlog]   # 命中即需确认
    confirm-patterns:
      - arguments: ".*admin.*"
    approval:
      enabled: true        # 总开关；false 退回旧行为
      ttl-seconds: 300     # 审批有效期（秒），超时自动过期
```

## 11. 关键决策（三轮子 Agent 审查结论）

1. **异常穿透**：不用哨兵字符串/ThreadLocal，直接抛 `ConfirmRequiredException`（Spring AI 不包装普通 RuntimeException，字节码证实）
2. **resume 防循环**：`completedTools` 不含待审批工具（它从未完成），必须显式预置，顺序 = 执行 → 预置 → 规划
3. **resume 传真实 ChatContext**：传 null 会导致数据权限工具「身份验证失败」、历史不落库
4. **confirm 事件独立 type**：不与 `progress.stage=confirm` 混用，避免前端从 progress 拆字段；结束信号是 EOF（无 `[DONE]`，与现有 SSE 一致）
5. **审批持久化 best-effort**：DB 失败降级内存快照，仍推确认事件（不可续流，可接受降级），绝不逃逸成硬流失败
6. **原子 CAS**：防双击/sweeper 竞态双执行

## 12. 测试与验证

- 单测：`GuardedToolCallbackTest`（CONFIRM 抛异常、approval 关闭退回字符串、callBypass 绕过守卫、ToolContext 透传）、`TaskPlannerTest`（CONFIRM 暂停建审批）、`SseUtilsTest`（结构化 confirm 事件）、`TaskExecutorTest`
- 手动端到端：建表 →「发布一篇测试博客」→ 弹卡片 → 确认看续流执行/拒绝看取消/改小 TTL 看过期 → 回归（普通查询不触发、deleteBlog 仍 BLOCK）

## 13. 已知取舍

- 回退路径（`TaskExecutor`，@Deprecated）resume 会重复执行已跑工具——接受降级
- 断线/导航中途取消审批 → 审批记录停留在 approved 未执行（`executed_at IS NULL`，可恢复）
