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

## 模块关系总图

```
                           ┌──────────────────────────┐
                           │      ChatController       │
                           │   /agent/string/send      │
                           └───────────┬──────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │    PromptHookChain       │ ← 前置处理
                          │  (注入检测 / 敏感词过滤)   │
                          └────────────┬────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │   AiServiceImpl Phase 1  │ ← 纯文本 LLM
                          │   ChatClient 无工具调用   │
                          │   3次重试 + 异常兜底      │
                          └────────────┬────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │    AfterAiHookChain      │ ← 后置决策
                          │   TaskTriggerHook        │
                          └──────┬──────────┬───────┘
                                 │          │
                     无需规划     │          │ 需要规划
                                 │          │
                          ┌──────┘          └──────┐
                          │                        │
                    ┌─────┴─────┐        ┌─────────┴─────────┐
                    │ 直接返回   │        │    TaskPlanner     │
                    └───────────┘        │  (专用线程池)       │
                                         │                    │
                                    ┌────┴────┐              │
                                    │decompose│ ← AI + 三层校验│
                                    ├─────────┤              │
                                    │ execute │ ← @Tool 调用  │
                                    ├─────────┤              │
                                    │  merge  │ ← LLM 聚合    │
                                    └────┬────┘              │
                                         │ (最多 5 轮)       │
                                         └──────────────────┘
                                         │
                          ┌──────────────┴──────────────┐
                          │     AiResponseRouter         │ ← 回复分发
                          │   SSE 流 / JSON 同步         │
                          └─────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                      安全横切面                               │
│                                                             │
│  ToolPermissionAspect ─── DataPermissionValidator (AOP)      │
│  GuardedToolCallback ─── ToolGuardManager (守卫投票)          │
└─────────────────────────────────────────────────────────────┘
```

---

## 数据流示例

```
用户: "帮我统计一下博客总数，再查一下北京的天气"

 1. [前置链] TaskTriggerHook 检测到触发词"统计""查"
    → 标记 ChatContext.planning = true
 2. [前置链] InjectionDetectHook + SensitiveWordHook 放行
 3. [Phase 1] AiServiceImpl 纯文本调用 → 回复"我来帮你处理"
 4. [后置链] AfterAiHookChain 看到 planning=true
    → 进入 Phase 2
 5. [TaskPlanner.decompose]
    → AI 分解: [{tool:"StatsQueryTool",args:{type:"blogCount"}},
                {tool:"WeatherQueryTool",args:{city:"北京"}}]
 6. [TaskPlanner.execute]
    → [TOOL_CALL] StatsQueryTool → { blogCount: 1523 }
    → [TOOL_CALL] WeatherQueryTool → { 温度: 25°C, 天气: 晴 }
 7. [TaskPlanner.merge]
    → LLM 聚合: "目前博客总数是 1523 篇，北京今天天气晴朗，25°C"
 8. [AiResponseRouter] SSE 流推送最终结果
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

### 关键设计原则

1. **可插拔**：策略/校验器/守卫/钩子均可独立新增，无需修改核心流程
2. **无感透明**：GuardedToolCallback 包裹、ToolPermissionAspect 切面对业务代码无侵入
3. **两阶段隔离**：Phase 1 轻量纯文本，Phase 2 重量规划，互不干扰
4. **输入→执行→回复**三段式清晰划分，每段可独立扩展
