# Agent 模块链路迭代文档

> 记录 AI Agent 模块从直接调用 LLM 到完整两阶段架构的演变过程。
> 每次迭代在输入处理、调用执行、回复处理三个维度上的演进。

---

## 迭代总览

```
Phase 0 ───→ Phase 1 ───→ Phase 2 ───→ Phase 3 ───→ Phase 4 ───→ Phase 5 ───→ Phase 6
基础调用     Agent化      流式支持      权限校验      守卫架构      任务规划      两阶段架构
                                                              └─ 输入处理(前置钩子)
                                                              └─ 回复处理(后置链)
     │
     ▼ 后续迭代（Phase 7–13：执行智能体化 + 全链路观测治理）
Phase 7 ───→ Phase 8 ───→ Phase 9 ───→ Phase 10 ───→ Phase 11 ───→ Phase 12 ───→ Phase 13
子Agent执行   全链路可观测   历史会话      提示词外置     CONFIRM审批   MaaS迁移     规划工具路由
```

---

## Phase 0：基础 AI 聊天功能

**提交**: `ae3393c` — "feat: 添加AI聊天功能基础架构"

### 架构

```
请求 → ChatController → AiServiceImpl → DashScope LLM → 回复 → 响应
```

### 输入处理
- 无 — 请求体直接透传给 LLM

### 调用执行
- `AiService` 接口定义 `chat(content)` 方法
- `ChatClient` 直接调用 DashScope 模型
- 同步阻塞等待 LLM 返回

### 回复处理
- 无 — LLM 原始文本直接返回给前端

### 核心代码
| 文件 | 职责 |
|------|------|
| `AiService.java` | 聊天服务接口 |
| `AiServiceImpl.java` | ChatClient 直接调用 |
| `ChatController.java` | /agent/chat 端点 |
| `AgentConfig.java` | ChatClient + DashScope 配置 |

### 局限
- 一问一答，无工具调用能力
- 无对话记忆（每次请求独立）
- 无流式输出

---

## Phase 1：Agent 化 + 工具调用

**提交**: `53b2688` — "feat(agent): 新增 AI Agent 模块，支持 SSE 双模对话与自动工具调用"

### 架构

```
                      ┌─ 同步 ─→ JSON 响应
请求 ─→ ChatController ┤
                      └─ SSE  ─→ 伪流式推送
                              │
          ┌───────────────────┴───────────────────┐
          │         AiServiceImpl                 │
          │  chatWithToolcall()                   │
          │  1. LLM 推理 → 决定工具               │
          │  2. 调用 @Tool 方法                    │
          │  3. LLM 聚合结果 → 回复                │
          └───────────────────────────────────────┘
                   │
          ┌────────┴────────┐
          │   ToolBeanCollector  │
          │   扫描 @TargetTool   │
          │   注册 ToolCallback  │
          └─────────────────────┘
```

### 输入处理
- **Accept 头协商**：`text/event-stream` 走 SSE 流模式，否则 JSON 同步
- **对话记忆**：`ChatMemory` 按 `conversationId` 存储历史

### 调用执行
- 升级 Spring AI 1.0.0 → 1.1.2，DashScope 1.0.0.2 → 1.1.2.0
- `@TargetTool` 注解标记工具 Bean
- `ToolBeanCollector` 启动时自动扫描所有 `@TargetTool` Bean，注册为 `ToolCallback[]`
- `AgentConfig` 将工具集合注入 `ChatClient.Builder` 的 `.defaultToolCallbacks()`
- 首个工具：`BlogTool`（博客查询/发布）、`WeatherQueryTool`（天气 Demo）
- 伪流式：先完整调用工具，再将结果分段 SSE 推送给前端

### 回复处理
- **同步模式**：直接返回 JSON 字符串
- **SSE 模式**：通过 `SseEmitter` 逐段推送

### 核心新增
| 文件 | 职责 |
|------|------|
| `TargetTool.java` | 工具 Bean 标记注解 |
| `ToolBeanCollector.java` | 自动扫描注册工具 |
| `BlogTool.java` | 博客查询/发布工具 |
| `WeatherQueryTool.java` | 天气查询工具（模拟） |
| `DashScopeHttpConfig.java` | 同步/流式双连接池 |

### 关键变化
```
输入:   无处理 → Accept 头协商 + 对话记忆
执行:   直调 LLM → LLM + 工具调用
回复:   原始文本 → SSE 分段推送
```

---

## Phase 2：SSE 真流式支持

**提交**: `96a0e6a` — "feat: add SSE streaming support to AiService"

### 架构

```
请求 → ChatController
         │
         ├─ /string/send (Accept: text/event-stream)
         │     └→ AiServiceImpl.chatStream()
         │          └→ ChatClient.stream().content()
         │               └→ Flux.subscribe(chunk → SseEmitter.send())
         │
         └─ /string/send (无 SSE) → JSON 同步响应
```

### 输入处理
- 同上 + 路由分发逻辑细化

### 调用执行
- `chatStream(content, SseEmitter)` 新增流式方法
- `ChatClient.stream().content()` 返回 `Flux<String>`
- Flux 订阅：逐 chunk 推送到 `SseEmitter`
- 终止标记：`[DONE]` 事件帧

### 回复处理
- **流式**：逐 token 推送到前端，用户可看到 LLM 逐字生成
- **错误**：错误 JSON 帧 + 中断
- 修复 `@Resource` 注入遗漏问题

---

## Phase 3：可插拔数据权限校验

**提交**: `dd859c7` — "refactor(agent): 重构数据权限校验为可插拔策略模式"

### 架构

```
工具调用请求
     │
     ▼
ToolPermissionAspect (AOP @Around)
     │
     ├─ 提取 userId ← ToolContext
     ├─ 获取 @RequiredDataPermission
     │    ├─ action = DataAction.READ/WRITE
     │    └─ target = 资源类型
     │
     ▼
PermissionValidatorFactory
     │
     ├─ BlogPermissionValidator: 校验博客归属权
     ├─ UserPermissionValidator: 校验用户身份
     └─ (可扩展) 新增资源只需加实现类

     ▼
 放行 / 拒绝
```

### 输入处理
- 新增 `@RequiredDataPermission` 注解，标记工具方法的权限需求
- `ToolPermissionAspect` 通过 AOP 拦截 `@TargetTool` 方法调用
- 从 `ToolContext` 提取当前用户 `userId`

### 调用执行
- **策略模式重构**：替代原 Service 层硬编码的 `checkPermission()`
- `DataPermissionValidator` 策略接口定义校验契约
- `PermissionValidatorFactory` 启动时自动收集所有校验器
- `BlogPermissionValidator`：通过 `blogService.getById()` 校验博客归属
- `UserPermissionValidator`：比较 `targetId` 与当前 `userId`
- 新资源只需创建 Validator 实现类，无需修改切面和工厂

### 核心新增
| 文件 | 职责 |
|------|------|
| `DataPermissionValidator.java` | 校验策略接口 |
| `PermissionValidatorFactory.java` | 校验器注册工厂 |
| `BlogPermissionValidator.java` | 博客归属权校验 |
| `UserPermissionValidator.java` | 用户身份校验 |
| `RequiredDataPermission.java` | 权限标记注解 |
| `DataAction.java` | 操作类型枚举 |
| `ToolPermissionAspect.java` | AOP 切面拦截 |

---

## Phase 4：可插拔工具守卫 PromptGuard

**提交**: `90410d6` — "feat(agent): 新增可插拔工具守卫 PromptGuard 架构"

### 架构

```
工具调用请求
     │
     ▼
GuardedToolCallback.call()
     │
     ▼
ToolGuardManager.evaluate()
     │
     ├─ Policy 1: HighRiskListPolicy
     │    匹配高风险工具名(如 drop_table) → BLOCK
     │
     ├─ Policy 2: ConfirmToolPolicy
     │    命中确认名单 → CONFIRM（需要用户确认）
     │
     ├─ Policy 3: PatternMatchPolicy
     │    正则匹配参数中的恶意模式 → BLOCK
     │
     └─ Policy 4: RateLimitPolicy
          Redis 滑动窗口限流 → ABSTAIN 或 BLOCK

     ▼
 投票汇总：ANY BLOCK → 拦截 | ANY CONFIRM → 确认 | 全 ABSTAIN → 放行
     │
     ▼
 真实工具调用 / 拒绝
```

### 输入处理
- 守卫层注入：`ToolBeanCollector` 收集 `ToolCallback[]` 后自动包裹 `GuardedToolCallback`
- 配置驱动：`application.yaml` 中 `promptguard.policies` 配置规则
- `RateLimitPolicy` 基于 Redis + Lua 滑动窗口限流

### 调用执行
- `ToolGuardPolicy` 策略接口，纯无状态逻辑
- `ToolGuardManager` 汇总各策略投票：
  - **ANY BLOCK** → 直接拦截
  - **ANY CONFIRM** → 返回确认提示
  - **全 ABSTAIN** → 放行
- `GuardedToolCallback` 透明包装：守卫逻辑在 `call()` 前插入，对 `AiServiceImpl` 无感
- `PromptGuardProperties` @ConfigurationProperties，规则完全由 YAML 驱动

### 回复处理
- 被拦截的工具 → 返回友好提示（"该操作需要管理员确认"等）
- 需要确认 → 返回确认消息 + 待确认令牌

### 核心新增
| 文件 | 职责 |
|------|------|
| `ToolGuardPolicy.java` | 守卫策略接口 |
| `ToolGuardManager.java` | 投票汇总管理器 |
| `GuardedToolCallback.java` | 透明守卫包装器 |
| `HighRiskListPolicy.java` | 高风险工具拦截 |
| `ConfirmToolPolicy.java` | 确认工具提示 |
| `PatternMatchPolicy.java` | 正则匹配拦截 |
| `RateLimitPolicy.java` | Redis 滑动窗口限流 |
| `ToolInvocationContext.java` | 调用上下文 |
| `Vote.java` | 投票结果枚举 |
| `GuardResult.java` | 守卫决策结果 |
| `PromptGuardProperties.java` | YAML 配置属性 |

---

## Phase 5：任务规划 + 前后处理链

**提交**: `c11c8de` — "feat: 实现任务规划与后处理链"

### 架构

```
请求 → ChatController
         │
         ▼
    AiServiceImpl (Phase 1)
         │ 纯文本 LLM 调用
         │
         ▼
    AfterAiHookChain
         │
         ├─ TaskTriggerHook: 检测触发词
         │   "统计"/"分析"/"对比"/"查询"/"搜索"/"推荐" 等
         │
         ├─ InjectionDetectHook: 提示注入检测
         │
         └─ SensitiveWordHook: 敏感词过滤
              │
              ▼
         PLANNING 决策 → 进入 Phase 2
              │
              ▼
         TaskPlanner
              │
         ┌────┴────┐
         │  decompose  │ ← AI 分解任务 + Java 三层校验
         │  execute    │ ← 串行调用 @Tool 方法
         │  merge      │ ← LLM 聚合各工具结论
         └────────────┘
         (最多 5 轮迭代)
              │
              ▼
         回复 → AiResponseRouter → SSE/JSON
```

### 输入处理（前置钩子 - PromptHook）
- `PromptHook` 接口定义前置处理契约
- `PromptHookChain` 链式执行所有钩子
- `TaskTriggerHook`：检测用户输入是否包含触发词（统计、分析、对比、查询等）
- `InjectionDetectHook`：检测提示注入攻击（Prompt Injection）
- `SensitiveWordHook`：敏感词过滤，拦截不当输入

### 调用执行
- **两阶段分工**：
  - **Phase 1**：纯文本 AI 回复（不绑定工具），走 `ChatClient` 基本调用
  - **Phase 2**：任务规划执行，走 `TaskPlanner` 编排

- **TaskPlanner 循环**（decompose → execute → merge）：
  1. **decompose**：LLM 分析用户意图，拆分为子任务列表
     - 经 Java 三层校验（格式 → 完整性 → 安全性）
     - 强制要求 `LLM_REASON` 字段（AI 解释为什么这么分解）
     - 空计划或非法计划走兜底逻辑
  2. **execute**：`TaskExecutor` 串行执行子任务
     - `TOOL_CALL` 类型：调用 @TargetTool 方法
     - `LLM_REASON` 类型：LLM 推理生成文本
  3. **merge**：LLM 聚合所有子任务结论，生成最终回复
  4. 最多迭代 5 轮，收敛后结束

- `SubTask` 模型：含任务类型、状态、输入参数、输出结果
- `TaskQueue`：子任务队列管理
- `TaskSnapshot`：任务快照，支持中途中断恢复

### 回复处理（后置链 - AfterAiHook）
- `AfterAiHook` 接口定义后处理契约
- `AfterAiHookChain` 链式执行
- `AiResponseRouter` 路由分发：
  - SSE 流模式 → 分阶段推送（开始 → 完成 → 完成对话）
  - JSON 同步模式 → 返回结构化工单结果
- `SseUtils`：JSON 事件构建工具（事件名、数据分段、[DONE] 终止）

### 核心新增
| 文件 | 职责 |
|------|------|
| `TaskPlanner.java` | 规划执行器（decompose→execute→merge） |
| `TaskExecutor.java` | 串行执行器 |
| `TaskQueue.java` | 子任务队列 |
| `SubTask.java` | 子任务模型 |
| `TaskReport.java` | 任务报告 |
| `TaskSnapshot.java` | 任务快照 |
| `SubTaskStatus.java` | 子任务状态枚举 |
| `TaskType.java` | 子任务类型枚举 |
| `AfterAiHook.java` | 后处理钩子接口 |
| `AfterAiHookChain.java` | 后处理链 |
| `TaskTriggerHook.java` | 触发词检测钩子 |
| `AiResponseRouter.java` | 响应路由分发 |
| `SseUtils.java` | SSE 事件构建工具 |
| `PromptHook.java` | 前置处理钩子接口 |
| `PromptHookChain.java` | 前置处理链 |
| `HookResult.java` | 钩子决策结果 |
| `ChatContext.java` | 对话上下文 |
| `StatsQueryTool.java` | 统计查询工具 |

---

## Phase 6：两阶段架构定型

**提交**: `f1643f5` — "refactor: 实施两阶段架构，Phase 1 纯文本 AI 不再自带工具"

### 架构

```
请求
 │
 ▼
PromptHookChain (前置处理)
 ├─ TaskTriggerHook: 判断是否需要规划
 ├─ InjectionDetectHook: 注入检测
 └─ SensitiveWordHook: 敏感词过滤
 │
 ▼
AiServiceImpl.chatWithToolcall() ← Phase 1
 │  纯文本 LLM 调用（无 .tools()）
 │  3 次重试 + 异常兜底
 │
 ▼
AfterAiHookChain (后置决策)
 │
 ├─ 无需规划 → 直接返回 Phase 1 回复
 │
 └─ 需要规划 → 进入 Phase 2
      │
      ▼
     TaskPlanner (专用线程池 subtaskExecutor)
      ├─ decompose (AI 规划 → Java 三层校验)
      ├─ execute (TOOL_CALL / LLM_REASON)
      └─ merge (LLM 聚合)
      │
      ▼
     AiResponseRouter → SSE / JSON 响应
```

### 输入处理
- Phase 1 前置链完整：`PromptHookChain` 包含注入检测 + 敏感词过滤
- 系统提示词更新：删除 "必须调用 queryWeather" 等硬编码指令

### 调用执行
- **Phase 1 剥离工具**：
  - 移除 `ChatClient` 的 `defaultToolCallbacks`
  - `chatWithToolcall` 改为纯文本调用（无 `.tools()` 绑定）
  - 新增 3 次重试机制 + 异常注入 prompt 提示
  - 失败时友好提示替代原始异常信息
  - 日志标记 `[Phase1]`
- **Phase 2 专用线程池**：`subtaskExecutor` 隔离任务规划线程
- **迭代上限**：最多 5 轮 decompose → execute → merge

### 回复处理
- `AiResponseRouter` 统一分发：根据请求类型（SSE/JSON）和阶段（Phase 1/2）路由

---

## Phase 7：子 Agent 规划执行（SubTaskAgent）

**提交**: `5f2f0d4` "feat(subagent): add data models for SubTaskAgent"、`a5d4a1c` "feat(subagent): add prompt builder and progress callback"、`ae8ffb8` "feat(subagent): implement SubTaskAgent executor"（07-28 feature 分支开发，08-06 经 `08168ce`/`95f61f6` 合入 master）

### 架构

```
TaskPlanner 主循环（最多 5 轮）
   decompose() → TOOL_CALL 任务列表
      │
      ├─ feature.subagent.enabled = true → 子 Agent 路径
      │     SubTaskPlan(userInput / tasks / historySummary / userId / round)
      │     SubTaskExecution(plan + SseSubAgentCallback + SubTaskProperties)
      │     SubTaskAgent.execute()
      │       ① filterCallbacks：按 plan.tasks 白名单过滤工具（防越权）
      │       ② PromptService 渲染执行 prompt（系统提示词只渲染一次）
      │       ③ subAgentChatClient 带工具调用（指数退避重试 + 总超时）
      │       ④ 解析 ===DATA_SNAPSHOT=== JSON 快照（data 500 字截断）
      │       ⑤ recordHistory → finalFailed 黑名单
      │
      └─ feature.subagent.enabled = false → 回退 TaskExecutor 串行直调
            （手动追加 LLM_REASON + TaskQueue 串行 + merge 聚合）
```

### 输入处理
- 每轮 decompose 校验后的 TOOL_CALL 列表组装 `SubTaskPlan`：原始 userInput、累积回复 currentResponse、本轮 tasks、历史摘要 historySummary（toolName → 50 字截断结果）、userId、conversationId（透传守卫，审批/限流按真实会话记账）、round
- 按 `plan.tasks` 的 toolName（`GuardedToolCallback.rawName` 匹配）动态过滤全部 ToolCallback，子 Agent 只能调用本轮计划内工具，杜绝越权（如写操作工具）
- 执行 prompt 与系统提示词经 PromptService 外置渲染；参数约束段强制工具参数值由系统设定，子 Agent 无权修改

### 调用执行
- 子 Agent 专用 `subAgentChatClient`（不绑默认工具、不加 ChatMemory），运行时 `.toolCallbacks(filteredCallbacks)` 注入 + `.toolContext(userId/conversationId)` 透传守卫层
- 带指数退避重试（retryBackoff 1s 起翻倍）+ `totalTimeout` 60s 总超时保护；失败时错误信息注入下一轮 prompt 重试；`ConfirmRequiredException` 不重试、原样上抛给 TaskPlanner
- LLM 自驱动工具调用循环（prompt 要求一次只调一个、结果纳入回答），结束附 `===DATA_SNAPSHOT===` JSON 快照（每个工具 status/data 或 status/message）
- 开关切换：`feature.subagent.enabled=true` 走子 Agent，false 回退原 TaskExecutor（灰度/故障切换零成本）

### 回复处理
- `parseResult` 解析快照：`status=ok` → rawResults、`status=error` → errors（allSuccess=false）；data 超 `RAW_DATA_MAX_LENGTH(500)` 截断防 Token 爆炸
- `summary` = 去快照标记的纯文本，作为本轮 currentResponse 进入下一轮规划；无快照/解析失败时降级全文作摘要，不阻断流程
- `recordHistory` 写 TaskReport：errors 置 retryCount=1 触发 finalFailed 黑名单，后续轮次 validatePlan 跳过不重复规划
- `SseSubAgentCallback` 推送 executing/merging 进度事件（SseUtils.safeSend 不阻断执行）

### 核心新增
| 文件 | 职责 |
|------|------|
| `subagent/SubTaskAgent.java` | 子 Agent 执行器（工具筛选 → 重试调用 → 快照解析 → 降级兜底） |
| `subagent/model/SubTaskPlan.java` | 输入模型（输入/任务/历史摘要/用户/会话/轮次） |
| `subagent/model/SubTaskExecution.java` | 执行上下文（plan + 回调 + 配置 + 总超时判定） |
| `subagent/model/SubTaskResult.java` | 输出（summary/rawResults/errors/allSuccess） |
| `subagent/callback/SseSubAgentCallback.java` | SSE 进度推送（onExecuteStart/onToolCall/onMergeStart/onError） |
| `subagent/prompt/SubAgentPromptBuilder.java` | 任务/参数约束/历史摘要变量构建（含 50 字截断） |
| `config/SubTaskProperties.java` | `agent.subtask.*`：timeout=30s / total-timeout=60s / max-retries=3 / retry-backoff=1s |

### 关键变化
```
输入:   TaskExecutor 逐工具参数 → SubTaskPlan 结构化（任务 + 历史摘要 + 身份透传）
执行:   Java 串行直调 + LLM_REASON + merge → 子 Agent 一次带工具 LLM 调用
回复:   工具结果 + 聚合 → DATA_SNAPSHOT 快照分离（自然语言摘要 + 结构化结果）
```

---

## Phase 8：全链路可观测性（Micrometer → OTel → OTLP → Langfuse 云）

**提交**: `203859d` "feat(observability): 观测核心包 AgentTracer/AgentSpan/ObservedSseEmitter + OTel 依赖"、`dadd9ff` "docs(observability): ObservedSseEmitter 设计方案与观测架构/排查文档"、`e21e82b` "feat(observability): 主链路接入会话根 span 生命周期收敛 + 工具/守卫埋点"

### 架构

```
业务埋点（Controller / AiServiceImpl / TaskPlanner / GuardedToolCallback …）
   │ AgentTracer.start(AgentSpanSpec, semantic)
   ▼
Micrometer Observation（agent.* / spring.ai.* / gen_ai.*）
   │ micrometer-tracing-bridge-otel
   ▼
OTel span（父子关系创建时固化、属性 onStop 统一同步）
   │ opentelemetry-exporter-otlp（OTLP /v1/traces）
   ▼
Langfuse 云（Basic 认证 + x-langfuse-ingestion-version:4 实时摄取）
   ▲
   │ 白名单 trace-filter：只放行 agent./spring.ai./gen_ai.（省配额）
   │ 跨线程：SpanContext/ChatContext 显式传播根 span，异步入口 resume()
   │ 收敛：ObservedSseEmitter —— 五条路径全部 → root.end()
```

### 输入处理
- ChatController 先创建会话根 span（`agent.session`，带 conversation.id / user.id 属性）再构造 emitter；三回调只留日志，end/finish 全部收敛到包装类
- 根 span 经 SpanContext/ChatContext 显式传到异步线程池（ThreadLocal 天然丢失），异步入口 `agentTracer.resume(root)` 重新挂载，后续子 span 自动挂树
- 配置：`management.otlp.tracing.endpoint`（实测 OTel SDK 1.43 不自动补 `/v1/traces`）+ `hmdp.ai-observability.trace-filter.include-prefixes` 白名单（追加而非覆盖）

### 调用执行
- **埋点分布**：`prompt_hook`、`phase1`（流式 LLM 整段）、`decision`、`round.{N}`、`plan`（plan.tools[] 摘要）、`subagent`、`tool_call.{工具}`、`guard.{决策}.{工具}`（决策进 span 名）、`prompt.{键}`、`llm_reason`
- `AgentTracer.start` 顺序契约：**先 start 后 openScope**（反之 `computeIfAbsent` 塞空 TracingContext → 每个 span 新开 traceId 断链）
- 跨线程防污染：同步段 finally 关 root scope（幂等），异步段靠 resume 重新挂载
- Spring AI 内置观测：子代理 generation 名加 `subagent-` 前缀与主代理 `chat <model>` 区分
- Fail-Open：埋点异常吞掉降级 NoopAgentSpan；属性经 AttributeSanitizer 脱敏（手机号/邮箱/身份证）+ 截断（摘要 200 / 诊断 4KB）

### 回复处理
- `ObservedSseEmitter` 是**根 span 唯一收敛点**：complete / completeWithError / 容器超时 / 容器错误 / 兜底 TTL 五条路径全部收敛到 `finish(reason)`（生产实测 onCompletion 在客户端断开时不触发 → 根 span 永不导出、子 span 平铺）
- `finish` only-once（CAS）：先写 finish 属性（onStop 同步到 span）再 `root.end()`，finally 兜底保证 end 必然执行
- 兜底 TTL 必须 > SSE_TIMEOUT（fail-fast 校验）；正常完成即 cancel 防高并发堆积
- `SseUtils.safeSend` 捕获终态后 send 抛的 IllegalStateException，避免业务误判"流式失败"触发无意义 LLM 重试

### 核心新增
| 文件 | 职责 |
|------|------|
| `observability/api/AgentTracer.java` | 观测门面（start/startSession/resume，埋点方唯一入口） |
| `observability/api/AgentSpan.java` | 业务 span 句柄（attribute/status/end，AutoCloseable） |
| `observability/api/ObservedSseEmitter.java` | SSE 生命周期与根 span 绑定（唯一收敛点） |
| `observability/core/SpanLifecycle.java` | Observation 生命周期封装（父子固化、start/openScope 顺序） |
| `observability/model/AgentSpanSpec.java` | span 类型注册表（`agent.{type}[.{semantic}]` 命名） |
| `observability/model/SpanContext.java` | 跨线程传播载体（rootSpan/conversationId/userId） |
| `observability/support/AttributeSanitizer.java` | 统一脱敏出口（脱敏 + 截断 200/4KB） |
| `observability/support/ObservabilityTraceFilter.java` | 观测白名单（源头过滤省 Langfuse 配额） |
| `observability/support/TraceProperties.java` | `hmdp.ai-observability` 配置（总开关 + 白名单） |

### 关键变化
```
输入:  请求直通 → 会话根 span 创建 + SSE 生命周期强绑定
执行:  无观测 → 全链路业务埋点 + Spring AI 内置观测（先 start 后 openScope 防断链）
回复:  emitter 直发 → 五路径统一收敛 root.end() + safeSend 静默终态
```

---

## Phase 9：历史会话落库与查询

**提交**: `c428f39` "feat(agent): 历史会话表 DDL 与实体/Mapper"、`a7dace2` "feat(agent): 历史会话服务与查询接口"、`72c1cbb` "feat(agent): 发送链路接入历史落库"

### 架构

```
发送链路  POST /agent/string/send（conversationId 首次 UUID 生成）
   │ ChatContext(userId=UserHolder / conversationId / originalContent)
   ▼
AiServiceImpl（JSON/SSE）→ Hook 链 → LLM
   │ AfterAiHook 决策：PASS 完整回复 / REPLACE 替换文本 / PLANNING 交 TaskPlanner / BLOCK 跳过
   ▼
AgentHistoryService.recordTurn（best-effort）→ agent_conversation / agent_message
   │
查询链路  GET /agent/conversations、GET /agent/conversations/{id}/messages → HistoryController
```

### 输入处理
- ChatContext 新增 `originalContent`（Hook 替换前的用户原始输入），由 AiServiceImpl 构造 ctx 时从原始 content 填充；userId 主线程捕获、conversationId 后端生成或前端续传
- 落库用原文而非 Hook 替换后文本，保证用户历史所见与所发一致；BLOCK 决策不落库（阻断原因非成功回合）

### 调用执行
- `AgentHistoryServiceImpl.recordTurn`（@Transactional）按 (conversationId, userId) 归属校验：首次建会话（title=首条消息截断 100 字、status=0），续传刷新 updated_at；每次写 user + assistant 两条消息
- 统一走 `recordTurnBestEffort`：失败仅记日志绝不抛——SSE 模式它在重试循环内，抛异常会重跑 LLM 浪费 token；JSON 模式抛异常会中断整个响应
- TaskPlanner.completeTurn 在 PLANNING 完成点落最终合并答案（中间 tool_call/thought 不落库）；CONFIRM 暂停路径与快照恢复路径跳过防重复落库

### 回复处理
- 查询接口挂 `/agent` 下登录拦截器保护（未登录 401）：会话列表按 updated_at DESC 含 message_count（LEFT JOIN 聚合防 N+1）；消息列表按 created_at ASC，先归属校验防跨用户泄露存在性

### 核心新增
| 文件 | 职责 |
|------|------|
| `entity/AgentConversation.java` / `entity/AgentMessage.java` | 会话元数据 / 消息明细实体 |
| `mapper/AgentConversationMapper.java` / `AgentMessageMapper.java` | 会话列表聚合 SQL / 消息 Mapper |
| `service/AgentHistoryService.java` / `impl/AgentHistoryServiceImpl.java` | 落库 + 查询（归属校验、建会话/续传、双消息） |
| `controller/HistoryController.java` | 查询接口（会话列表 + 会话消息列表） |
| `dto/ConversationVO.java` / `dto/MessageVO.java` | 查询 VO（不吐 Entity） |
| `hook/ChatContext.java` / `service/impl/AiServiceImpl.java` / `task/TaskPlanner.java` | 改动：originalContent + 发送链路/完成点接入落库 |

### 关键变化
```
输入:  ChatContext 新增 originalContent（Hook 替换前原文）
执行:  成功回合按 (conversationId, userId) 归属校验落库，recordTurn 统一 best-effort
回复:  按 AfterAiHook 决策分派落库；新增查询接口防跨用户泄露
```

---

## Phase 10：提示词外置（Langfuse Prompt Management + 多级缓存预热）

**提交**: `0e3d294` "feat(agent): 提示词外置（Langfuse Prompt Management + 内置兜底）"、`72b4b27` "fix(agent): Langfuse prompt 404 走负缓存而非瞬时熔断"、`c8cf133` "fix(agent): Langfuse 4xx 用空 handler 而非 res.close()"、`7c8ff14` "fix(agent): Langfuse 拉取请求补 Authorization 头"、`e066d10` "perf(agent): 启动预热全部提示词缓存，缓存 TTL 5m 调长到 30m"

### 架构

```
render(KEY, vars) / renderTool(toolKey, vars)
   │ 调用点：AiServiceImpl / TaskPlanner / SubTaskAgent / TaskExecutor / ExternalizedToolDefinitionProvider
   ▼
DefaultPromptService（编排 + 埋 agent.prompt.{key} span）
   ├─ 未启用 / 未配置（base-url|basic-auth 空）—— Fail-Open
   │     └→ BuiltinPromptRepository（classpath:prompts/{key}.txt 首读后常驻兜底）
   └─ 启用 → LangfusePromptRepository.fetch(key)
        ├─ contentCache 命中（成功文本 / 404 负缓存，TTL 30m）
        ├─ failureCache 命中（30s 熔断）→ empty
        └─ 未命中 → GET /api/public/prompts?name=key&label=production
             2xx→contentCache / 4xx→负缓存 / 5xx·超时→failureCache
   ▼
PromptRenderer.render（{{var}} 替换，永不抛）→ ChatClient / 工具 schema 覆盖
```

### 输入处理
- 外置开关：`agent.prompt.enabled` 总开关 + `isConfigured()`（base-url/basic-auth 双非空才发远程请求）；本地无环境变量时零远程请求、全走内置（Fail-Open）
- 系统提示词动态化：删除静态 defaultSystem，5 个 LLM 调用点每次请求 `promptService.render(KEY, vars)` 显式 `.system()` 注入，支持按用户个性化（userId 变量）
- 渲染器 `{{var}}` 替换：缺失保留字面量 + WARN（比静默替换为空好调试），变量值含 `$`/`\` 用 `Matcher.quoteReplacement` 防误替换，模板为 null → 空串，永不抛异常

### 调用执行
- **多级缓存**：contentCache（成功文本 + 404 负缓存 TTL 30m）/ failureCache（瞬时故障 30s）/ 内置文件首读常驻 / 工具描述 resolvedCache 免重复 JSON parse
- **404 负缓存 vs 瞬时熔断**：4xx（404 不存在 / 401 认证失败）是确定性结果 → 写负缓存防每请求刷 404；5xx/超时 → 写 failureCache 熔断 30s，熔断期返回 empty、过后自动恢复探测
- 关键修复：4xx 用空 handler 而非 `res.close()`（close 后 body 无法提取，抛 "Error while extracting response" 被误判瞬时故障）；RestClient 构建补 Authorization 头（之前漏了全 401 → 被负缓存误判「无此 prompt」）
- 工具描述外置：renderTool 解析 `{"description","params"}` → ExternalizedToolDefinitionProvider 在 `getToolDefinition()` 重建 ToolDefinition，覆盖 description + 参数描述，自动传播到 LLM 函数 schema；优先级 Langfuse → 内置 → @Tool 注解
- 启动预热：`PromptCacheWarmer` parallelStream 预拉全部 13 个 key（6 文本 + 7 工具）进缓存，消除首读延迟；未配置/未启用跳过、失败由熔断兜底不阻塞启动

### 回复处理
- 绝不抛异常：模板缺失/渲染异常 → 文本返回空串 / 工具描述返回 empty（回退 @Tool 注解）
- 热治理：`/agent/prompt/reload` 清双缓存热生效 + `/agent/prompt/seed` 把内置推 Langfuse production label（seed-enabled 默认关闭保护）

### 核心新增
| 文件 | 职责 |
|------|------|
| `prompt/PromptService.java` / `DefaultPromptService.java` | 门面：render / renderTool（Langfuse→内置→Fail-Open） |
| `prompt/PromptKeys.java` | 键常量单一事实源（Langfuse 名 = 资源文件名） |
| `prompt/PromptRenderer.java` | `{{var}}` 占位符渲染，永不抛 |
| `prompt/PromptProperties.java` | `agent.prompt.*` 配置（9 项） |
| `prompt/repo/LangfusePromptRepository.java` | 远程拉取 + 双 Caffeine 缓存 |
| `prompt/repo/BuiltinPromptRepository.java` | 内置模板兜底 |
| `prompt/PromptCacheWarmer.java` / `PromptSeeder.java` / `PromptAdminController.java` | 启动预热 / 种子 / 管理端点 |

### 关键变化
```
输入:  静态 defaultSystem → 每次请求 PromptService 动态渲染
执行:  内置模板 → Langfuse 远程优先 + 多级缓存 + 404 负缓存 + 30s 熔断 + 启动预热
回复:  纯字符串 → 工具描述外置覆盖 LLM 函数 schema
治理:  改代码重启 → Langfuse UI 直接编辑 + reload 清缓存热生效
```

---

## Phase 11：CONFIRM 审批真暂停 + agent_approval 审批流

**提交**: `f4bba6f` "feat(agent): CONFIRM 审批真暂停 + agent_approval 审批流"（设计文档 `ebf8c57` / `cd4f8ee`）

### 架构

```
工具调用 → GuardedToolCallback.call()
   ├─ ALLOW → delegate.call()
   ├─ BLOCK → 错误字符串
   └─ CONFIRM → approvalEnabled？
        ├─ 是 → throw ConfirmRequiredException（携带 ToolInvocationContext）
        └─ 否 → 确认提示字符串当工具结果喂回 LLM（旧行为）
             │
             ▼
        SubTaskAgent / TaskExecutor 最前 catch{ throw e; }（不重试）
             ▼
        TaskPlanner round 外层：① 建 TaskSnapshot ② ApprovalService 建审批(pending, TTL)
        ③ taskScheduler 过期 + @Scheduled sweeper 兜底 ④ 推 type:confirm 事件（流中最后 data，EOF 停流）
        ⑤ completeTurn 识别暂停态：跳过尾文本与历史落库
             │
用户确认 POST /agent/confirm（Accept: text/event-stream → SSE 续流）
   → 原子 CAS pending→approved（防双击/sweeper 竞态）
   → 新 ObservedSseEmitter + TaskSnapshot.fromApproval + resumeFromSnapshot
       ① callBypass 直调待审批工具（已批准不再二次投票）
       ② 预置 history = completedTools ∪ {待审批工具}（全 COMPLETED）
       ③ 续接 partialResponse + 工具结果后再进规划循环（防二次审批死循环）
```

### 输入处理
- 确认名单配置驱动：`confirm-tools` 精确匹配 + `confirm-patterns` 正则 → ConfirmToolPolicy 投 CONFIRM
- `approval.enabled`（默认 true）总开关，false 时 CONFIRM 退回旧行为；`ttl-seconds`（默认 300）审批有效期；决策一律用记录里的 user_id（异步线程无 UserHolder）

### 调用执行
- **真暂停信号**：CONFIRM 抛 ConfirmRequiredException——普通 RuntimeException，Spring AI 只包装 ToolExecutionException 故原样穿透；SubTaskAgent/TaskExecutor 的 catch 最前 rethrow 防被重试/标记失败吞掉
- **审批状态机**：pending → approved / rejected / expired；markApproved 原子 CAS（WHERE status='pending' AND expired_at>=now，校验影响行数），markExecuted 记 executed_at 防重复执行
- 超时双保险：taskScheduler 懒过期 + @Scheduled(fixedDelay=60s) sweeper 批量清扫
- conversationId 修复：GuardedToolCallback 改从 ToolContext 读真实会话（原构造时冻结启动 UUID）

### 回复处理
- 暂停侧：推独立 `type=confirm` 事件（不复用 progress.stage），是流中最后一个 data 随后 EOF；completeTurn 检测 pendingSnapshot != null 跳过尾文本与历史落库
- 续流侧：resumeFromSnapshot **恢复顺序决定成败**——先 callBypass 执行待审批工具，再预置 history，最后进规划循环；拒绝/过期/越权返回对应错误，DB 降级时 confirmId 为空前端仅提示不可续流

### 核心新增
| 文件 | 职责 |
|------|------|
| `guard/ConfirmRequiredException.java` | CONFIRM 异常信号（携带 ToolInvocationContext，穿透 Spring AI 工具链） |
| `service/ApprovalService.java` / `impl/ApprovalServiceImpl.java` | 审批创建、原子 CAS 状态翻转、sweeper |
| `entity/AgentApproval.java` / `mapper/AgentApprovalMapper.java` | agent_approval 表实体 + Mapper |
| `task/TaskSnapshot.java` | 任务快照（暂停进度/待审批工具，fromApproval 重建） |
| `GuardedToolCallback.callBypass()` | 绕过守卫直调底层（仅审批恢复路径） |
| `guard/policy/ConfirmToolPolicy.java` | 确认名单策略（confirm-tools 命中投 CONFIRM） |
| `controller/ChatController.confirm()/reject()` | POST /agent/confirm（双模 SSE 续流）+ /agent/reject |

### 关键变化
```
输入:  守卫 CONFIRM → 配置驱动确认名单 + 审批开关/TTL
执行:  返回提示字符串 → 抛异常真暂停（快照 + 审批记录 + CAS 状态机）
回复:  确认提示文本 → type:confirm 事件 + 用户确认后 SSE 续流恢复
```

---

## Phase 12：MaaS OpenAI 兼容迁移 + 工具描述按需解析

**提交**: `a72834c` "fix(agent): DashScope base-url 改用 spring-ai-alibaba 认可属性，并指向 MaaS 原生端点"、`eccb240` "chore(agent): 检查点提交（按需访问器 + MaaS OpenAI 迁移 + observability）"

### 架构

```
ChatClient（OpenAiChatModel ← OpenAiHttpConfig 连接池）
   │ base-url=…/compatible-mode，SDK 自动拼 /v1/chat/completions（OpenAI 请求体）
   ▼
MaaS 兼容端点 ws-…maas.aliyuncs.com/compatible-mode/v1/chat/completions
   └─ options.model = qwen-plus-2025-07-28

ToolCallback[]（GuardedToolCallback 透明包裹）
   ├─ 按需访问器（规划/过滤/校验阶段，只读注解、零外置拉取）
   │   rawName / rawDescription / getRawInputSchema
   │   └→ delegate.getToolDefinition()（@Tool/@ToolParam 原始定义）
   │       使用者：TaskPlanner 目录+校验、SubTaskAgent 过滤、ToolRouter 紧凑目录
   └─ getToolDefinition()（执行阶段，按需外置覆盖）
        └→ ToolDefinitionProvider.resolve(delegate)
             └→ ExternalizedToolDefinitionProvider（Langfuse→内置→注解，Caffeine 缓存）
```

### 输入处理
- 规划阶段工具清单改为按名操作：TaskPlanner 目录构建用 rawName + rawDescription、validatePlan 回调索引用 rawName，不再为全量工具触发外置 Langfuse 解析
- SubTaskAgent.filterCallbacks 按 rawName 白名单过滤只留本轮计划工具；外置描述延迟到执行构建保留工具 schema 时才按需解析

### 调用执行
- **迁移动机**：MaaS 工作空间端点只认 OpenAI 兼容路径 `/compatible-mode/v1/chat/completions`；DashScope 经典路径 `/api/v1/services/aigc/text-generation/generation` 被网关静默丢连接（NoHttpResponseException），DashScope 格式请求体打兼容端点返回 400
- pom 弃 `spring-ai-alibaba-starter-dashscope` → `spring-ai-starter-model-openai`；注入类型由 DashScopeChatModel 改为 ChatModel
- OpenAiHttpConfig 提供 @Primary customOpenAiApi Bean：同步 Apache HttpClient 5 连接池（200/30s/10s 超时）+ 流式 Reactor Netty（responseTimeout 对齐 SseEmitter 30min 防断流）
- base-url 到 `/compatible-mode` 为止（带 /v1 拼成 …/v1/v1 404）；模型必须配 options.model（OpenAiChatProperties 无 model 字段，chat.model 被忽略、默认 gpt-4o-mini 不认会 404/断连）

### 回复处理
- OpenAI 兼容流式**首 chunk 只有 role、content=null**：Flux.map 不允许 mapper 返回 null，先转空串再按空串过滤，消除 NPE（此前 DashScope 适配器直接返回文本）
- ChatModelObservationConventionConfig：子代理调用前 mark("subagent")（finally clear），Langfuse generation 名变 `subagent-chat <model>` 与主代理 `chat <model>` 区分

### 核心新增
| 文件 | 职责 |
|------|------|
| `config/OpenAiHttpConfig.java` | OpenAI 客户端连接池（HttpClient5 + Netty），@Primary customOpenAiApi |
| `config/ChatModelObservationConventionConfig.java` | ThreadLocal 调用方打标，重写 generation span 名 |
| `tool/ToolDefinitionProvider.java` | 工具描述外置覆盖点接口（失败回退注解） |
| `tool/ExternalizedToolDefinitionProvider.java` | Langfuse→内置→注解 三级解析 + Caffeine 缓存 |
| `GuardedToolCallback` 新增访问器 | rawName / rawDescription / getRawInputSchema（实例 + 静态） |

### 关键变化
```
输入:  全量外置描述目录 → 按名紧凑目录（raw 访问器，规划阶段零外置拉取）
执行:  DashScope 经典 SDK → OpenAI MaaS compatible-mode（ChatModel + options.model）
回复:  流式 null chunk 防空指针；Langfuse generation 名按调用方区分
```

---

## Phase 13：规划工具路由（紧凑目录 + 一体化路由 + UNCERTAIN 保底）

**提交**: `7c15746` "docs(agent): 收录规划工具路由设计文档"、`9275d77` "feat(agent): 工具路由前置——rawInputSchema 访问器 + tool-routing 功能开关"、`65043eb` "feat(agent): 新增路由组件 CompactCatalogBuilder + ToolRouter"、`3138016` "refactor(agent): TaskPlanner 规划编排切换紧凑目录，__UNCERTAIN__ 时全量重跑一次"

### 架构

```
askAiForPlan（编排）
   │ compact = feature.tool-routing.enabled
   ▼
ToolRouter.buildCatalog(compact, cbs, history)
   ├─ compact=true → CompactCatalogBuilder
   │     `- 工具名: 首句标签（参数：city, title）`
   │       OVERRIDES 优先（publishTestBlog 补「发博客/写博客/发布」触发词）
   │       首句 = 描述 split("。",2)[0] 压空白；空描述 →（无描述）
   │       参数名从 rawInputSchema properties key 解析（防 LLM 猜错 key）
   │       超 maxTagLength 截断加 …；跳过 history 已完成/终失败工具
   └─ compact=false → 全量目录（名字 + 完整注解描述，原 askAiForPlan 逻辑）
   ▼
plannerCall → 规划 LLM
   ▼
isUncertain(result)？→ 含 __UNCERTAIN__ → 全量目录重跑一次（有界，每轮最多一次）
   ▼
validatePlan（callbackIndex 保持全量，防子集外工具误判「工具不存在」）
```

### 输入处理
- 规划 prompt 模板两处微调：「可用工具」下加「工具仅有名称+简介，先按语义匹配；若无法确定匹配，输出 {"tool":"__UNCERTAIN__"}」；参数规则改为「参数名必须与工具简介括号中标注的参数完全一致」
- 全量工具（名字+完整描述）进规划 prompt → 紧凑目录（名字+短标签+参数名），token 大幅压缩

### 调用执行
- `CompactCatalogBuilder`（纯逻辑可独立单测）：OVERRIDES 表（工具元数据入代码不入 yml）→ 首句标签 →（无描述）→ 拼参数名 → 截断加 …；目录构建跳过 history 已完成/终失败
- `ToolRouter` 门面：buildCatalog(compact) 紧凑/全量切换 + isUncertain 检测 + UNCERTAIN_MARKER 常量
- TaskPlanner.askAiForPlan 只做编排（抽 plannerCall）：compact → 调 LLM → 不确定则全量重跑一次；__UNCERTAIN__ 在进 validatePlan 前由编排层拦截；decompose/validatePlan 不动
- 前置依赖：GuardedToolCallback 补 getRawInputSchema()（读注解、不触发外置解析）；FeatureProperties.ToolRouting（enabled=true、maxTagLength=60），yml `feature.tool-routing.*`

### 回复处理
- 保底有界：每轮最多一次全量重跑；仍不确定 → 空计划 → validatePlan 返回空 → 保持原 AI 回复终止该轮（MAX_ROUNDS=5 总闸）
- enabled=false 或 getToolRouting()==null → 直接全量目录，与现状零行为差异（Fail-Open）

### 核心新增
| 文件 | 职责 |
|------|------|
| `routing/CompactCatalogBuilder.java` | 紧凑目录构建（OVERRIDES/首句/参数名/截断，纯逻辑） |
| `routing/ToolRouter.java` | 路由门面（buildCatalog 紧凑/全量 + isUncertain） |
| `GuardedToolCallback.getRawInputSchema()` | 原始输入 schema 访问器（参数名提取，不触发外置解析） |
| `config/FeatureProperties.ToolRouting` | tool-routing 开关（enabled/maxTagLength） |

### 关键变化
```
输入:  全量工具描述目录 → 紧凑目录（名字 + 首句标签 + 参数名）
执行:  规划 prompt 一次调用完成路由 + 定参；识别不出 → UNCERTAIN 标记 → 全量重跑一次
回复:  省 token（对比 gen_ai.usage.input_tokens）；保底不破坏规划准确性
```

---

## 模块关系总图

```
                           ┌──────────────────────────┐
                           │      ChatController       │
                           │ /agent/string/send ·confirm│
                           │ ·reject ·conversations    │
                           └───────────┬──────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │    PromptHookChain       │ ← 前置处理
                          │ (注入检测/敏感词/TaskTrigger)│
                          └────────────┬────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │   AiServiceImpl Phase 1  │ ← 纯文本 LLM（3次重试）
                          │   PromptService.render   │ ← 提示词外置(Langfuse→内置)
                          └────────────┬────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │    AfterAiHookChain      │ ← 后置决策
                          └──────┬──────────┬───────┘
                     无需规划     │          │ 需要规划
                          │          │
                    ┌─────┴─────┐  ┌─────────┴─────────┐
                    │ 直接返回   │  │    TaskPlanner     │
                    └───────────┘  │  (专用线程池)       │
                                   │ askAiForPlan       │ ← ToolRouter 紧凑目录 + UNCERTAIN 保底
                                   │ decompose/validate │ ← callbackIndex 全量校验
                                   │ execute            │ ← SubTaskAgent(子Agent)/TaskExecutor 回退
                                   │ CONFIRM 暂停/续流   │ ← agent_approval 审批流
                                   └─────────┬─────────┘
                                             │ (最多 5 轮)
                                             ▼
                          ┌──────────────────────────┐
                          │     AiResponseRouter      │ ← 回复分发
                          │   SSE 流 / JSON 同步      │
                          │   历史会话落库 recordTurn  │
                          └──────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 观测横切面：AgentTracer → OTel → Langfuse 云                 │
│   ObservedSseEmitter 收敛会话根 span（五路径 → root.end）      │
├─────────────────────────────────────────────────────────────┤
│ 安全横切面：ToolPermissionAspect(数据权限)                    │
│   GuardedToolCallback → ToolGuardManager(守卫投票 + CONFIRM) │
└─────────────────────────────────────────────────────────────┘
```

---

## 数据流示例

```
用户: "查看我的博客以及长沙天气"

 1. [前置链] TaskTriggerHook 检测到触发词 → 标记 ChatContext.planning = true；注入/敏感词放行
 2. [Phase 1] AiServiceImpl 纯文本调用（PromptService 渲染，Langfuse/内置兜底）→ 回复"好的，我来帮你查"
 3. [后置链] AfterAiHookChain 看到 planning=true → 进入 Phase 2
 4. [TaskPlanner.askAiForPlan] ToolRouter 生成紧凑目录（queryWeather/queryPublishedBlogs/… 名字+短标签+参数名）
    → 规划 LLM 一次调用完成路由+定参
    → [{tool:"queryPublishedBlogs",params:{}},
        {tool:"queryWeather",params:{city:"长沙"}}]
    → 若识别不出输出 {"tool":"__UNCERTAIN__"} → 全量目录重跑一次（保底）
 5. [validatePlan] callbackIndex 全量校验 → 过滤已完成/终失败 → TOOL_CALL 列表
 6. [SubTaskAgent.execute] 子 Agent 带工具调用：
    → queryPublishedBlogs → 我的博客列表（前 10 条按点赞降序）
    → queryWeather(city="长沙") → 长沙天气
 7. [TaskReport.recordHistory] 结果进 history，失败进 finalFailed 黑名单（后续轮次跳过）
 8. [TaskPlanner.completeTurn] 落最终回复 → 历史会话落库（agent_conversation / agent_message）
 9. [AiResponseRouter] SSE 流推送；AgentTracer 埋点 → Langfuse 云（ObservedSseEmitter 收敛根 span）
```

---

## 迭代演进总结

| 维度 | Phase 0 | Phase 1 | Phase 2 | Phase 3 | Phase 4 | Phase 5 | Phase 6 |
|------|---------|---------|---------|---------|---------|---------|---------|
| **输入处理** | 无 | Accept 协商 | — | 权限注解 | 守卫评估 | 前置钩子链 | 注入+敏感词 |
| **调用执行** | 直调 LLM | LLM+工具 | 流式 Flux | AOP 权限 | 守卫投票 | 任务规划 | 两阶段分工 |
| **回复处理** | 原始文本 | SSE 分段 | 逐 token | — | 拦截提示 | 后置链+路由 | 路由分发 |
| **工具注册** | 无 | @TargetTool | — | — | Guarded包装 | 扩展工具 | — |
| **安全性** | 无 | 无 | 无 | AOP 权限 | 四策略守卫 | 注入+敏感词 | 完整安全层 |

| 维度 | Phase 7 | Phase 8 | Phase 9 | Phase 10 | Phase 11 | Phase 12 | Phase 13 |
|------|---------|---------|---------|----------|----------|----------|----------|
| **输入处理** | 子Agent任务摘要 | 会话根span创建 | originalContent | 提示词动态渲染 | 配置驱动确认名单 | 按名紧凑目录 | 紧凑目录+UNCERTAIN指示 |
| **调用执行** | 子Agent带工具调用 | 全链路业务埋点 | (conversationId,userId)归属落库 | Langfuse远程+多级缓存 | 审批CAS状态机 | MaaS OpenAI迁移 | 一次调用路由+定参 |
| **回复处理** | DATA_SNAPSHOT快照分离 | ObservedSseEmitter根收敛 | 历史查询接口 | 工具描述覆盖schema | type:confirm事件+SSE续流 | 流式null chunk防空 | UNCERTAIN全量重跑保底 |
| **工具注册** | 按计划动态过滤 | — | — | 描述外置(三级解析) | callBypass审批直调 | 按需访问器rawName等 | 紧凑目录构建 |
| **安全性** | 防越权调用 | 属性脱敏+白名单 | 跨用户归属校验 | — | 高风险写操作审批暂停 | — | — |

### 关键设计原则

1. **可插拔**：策略/校验器/守卫/钩子/路由组件均可独立新增，无需修改核心流程
2. **无感透明**：GuardedToolCallback 包裹、ToolPermissionAspect 切面对业务代码无侵入
3. **两阶段隔离**：Phase 1 轻量纯文本，Phase 2 重量规划，互不干扰
4. **输入→执行→回复**三段式清晰划分，每段可独立扩展
5. **提示词可治理**：提示词外置 Langfuse，`reload` 清缓存热生效，改提示词不重启服务
6. **观测贯穿**：全链路 span 埋点 + ObservedSseEmitter 根 span 收敛 + 属性脱敏，排查有据
7. **Fail-Open 保底**：提示词外置未配置、工具路由关闭等一律回落现状，零行为差异
8. **审批可暂停**：高风险写操作 CONFIRM 真暂停，快照落库后确认续流，误放可拒
