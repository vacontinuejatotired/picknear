# Agent 模块处理流程

> 本文档描述一个用户请求从输入到输出的**完整业务流程**，聚焦框架自定义组件的职责与协作关系。
> AI 模型内部推理过程不展开，SSE 传输协议与观测细节另见专项文档。

---

## 1. 全局流程概览

```mermaid
flowchart TD
    A["🖥️ 用户请求<br/>POST /agent/string/send"] --> B["🔀 ChatController<br/>内容协商（JSON / SSE）"]
    B --> C["📦 AgentContext 构建<br/>userId + conversationId + history + rootSpan"]
    C --> D["⛓️ PromptHookExecutor<br/>前置 Hook 链执行"]
    D -->|BLOCK| E["❌ 返回阻断原因"]
    D -->|PASS / REPLACE| F["🤖 Phase 1：纯文本 LLM 调用<br/>（不带任何工具）"]
    F --> G["⛓️ AfterAiHookChain<br/>后处理 Hook 链执行"]
    G -->|BLOCK| H["❌ 返回错误"]
    G -->|REPLACE| I["📤 推送替换文本"]
    G -->|PASS| J["📤 推送原始回复"]
    G -->|"PLANNING（触发词命中）"| K["🗺️ Phase 2：TaskPlanner<br/>规划执行主循环"]
    K --> L["🔄 Round N"]
    L --> M["📐 decompose() 规划拆解"]
    M -->|空计划| J
    M -->|有任务| N{"feature.subagent.enabled?"}
    N -->|true| O["🧒 SubAgentRoundExecutor<br/>子 Agent 执行分支"]
    N -->|false| P["🔄 FallbackRoundExecutor<br/>回退串行分支"]
    O --> Q["🔁 循环直到 MAX_ROUNDS<br/>或空计划退出"]
    P --> Q
    Q --> R["📤 推送最终结论"]

    style A fill:#e1f5fe
    style F fill:#fff3e0
    style K fill:#fce4ec
    style O fill:#e8f5e9
    style P fill:#f3e5f5
```

---

## 2. 请求入口：ChatController

所有请求经由 **`ChatController`**（`/agent/string/send`）统一入口，根据 `Accept` 头协商响应模式：

| 模式 | 执行方式 |
|------|----------|
| **SSE 流式（唯一对话模式）** | 异步推送，支持工具调用全流程；JSON 同步对话已废弃（2026-09-03） |

**入口动作（SSE）：**

```mermaid
flowchart LR
    A["请求到达"] --> B{"conversationId<br/>是否传入？"}
    B -->|否| C["UUID 自动生成"]
    B -->|是| D["续传会话"]
    C --> E["回放历史（ConversationReplayService）"]
    D --> E
    E --> F["构建 AgentContext<br/>存入 AgentContextHolder"]
    F --> G["委托 AiService"]
    G --> H["finally: AgentContextHolder.clear()"]
```

> `AgentContext` 是贯穿整个请求链路的**上下文载体**，包含 `userId`、`conversationId`、`originalInput`、`history`、`rootSpan`，通过 `AgentContextHolder`（ThreadLocal）传递，异步段由 `AgentContextPropagator` 自动传播。

---

## 3. 前置 Hook 链：PromptHookExecutor

**组件：** `PromptHookExecutor` → `PromptHookChain` → 各 Hook 实现

AI 调用**之前**执行的拦截链（SSE 对话共用）。职责：安全检测、输入清洗、敏感词脱敏（`SensitiveWordHook` 默认关闭，见源码注解与《上下文压缩子系统设计文档》§0）。

```mermaid
flowchart TD
    A["用户原始输入 originalInput"] --> B["PromptHookChain.execute()"]
    B --> C{"InjectionDetectHook<br/>注入检测"}
    C -->|命中| D["BLOCK ❌<br/>返回拦截原因"]
    C -->|未命中| E{"SensitiveWordHook<br/>敏感词脱敏"}
    E -->|命中| F["REPLACE 🔧<br/>替换为脱敏文本<br/>currentInput = replaced"]
    E -->|未命中| G["PASS ✅<br/>继续下一个 Hook"]
    F --> H{"后续 Hook..."}
    G --> H
    H --> I["返回 HookResult<br/>（decision + finalContent）"]

    style D fill:#ffcdd2
    style F fill:#fff9c4
    style I fill:#c8e6c9
```

### Hook 执行规则

| 决策 | 行为 | 后续 |
|------|------|------|
| **PASS** | `currentInput` 不变 | 继续下一个 Hook |
| **REPLACE** | 替换 `currentInput`，替换结果成为后续 Hook 的输入 | 继续 |
| **BLOCK** | 立即短路，返回该 BLOCK | 后续 Hook 不再执行 |
| **异常** | Fail-Open：捕获异常，降级为 PASS | 继续 |

### 已注册 Hook

| Hook | 类型 | 职责 |
|------|------|------|
| `InjectionDetectHook` | `PromptHook` | 检测 Prompt 注入攻击特征（"忽略之前的指令"、"you are now" 等） |
| `SensitiveWordHook` | `PromptHook` | 敏感词检测并脱敏替换（"攻击银行" → "****"） |

> Hook 链输出 `HookOutcome`：未阻断时携带 `finalContent`（最终送 LLM 的文本）和 `AgentContext`；阻断时携带 `blockReason`。

---

## 4. Phase 1：纯文本 LLM 调用

**组件：** `StreamingChatInvoker`（SSE）/ `ChatClient`（JSON）

Phase 1 **不带任何工具**，AI 只做纯文本理解和初步回复。

```mermaid
flowchart LR
    A["systemText<br/>PromptService.render('agent.system.main')"] --> B["用户输入<br/>finalContent"]
    B --> C["LLM 纯文本调用"]
    C --> D["完整回复<br/>fullResponse"]
```

### 关键设计：为什么 Phase 1 不带工具？

| 方案 | 问题 |
|------|------|
| Phase 1 就绑定工具 | AI 第一次回复直接调工具，规划器又规划一遍 → 重复执行 |
| **Phase 1 纯文本** ✅ | 工具只在 Phase 2 执行一次，无重复，日志清晰 |

### SSE 路径的重试机制（StreamingChatInvoker）

```mermaid
flowchart TD
    A["streamWithRetry()"] --> B["attempt 1"]
    B -->|成功| C["返回 fullResponse"]
    B -->|失败| D["错误回喂 LLM<br/>initialContent + 系统提示重试"]
    D --> E["attempt 2"]
    E -->|成功| C
    E -->|失败| F["attempt 3"]
    F -->|成功| C
    F -->|失败| G["返回 StreamOutcome(failed)"]
```

- 最多 3 次重试，失败后将错误信息回喂 LLM 让其自行修正
- SSE 模式下通过 `ChatModel` 层流式直调（绕开 `ChatClient` 层以修复断链问题）
- 每个 token 逐段推送给前端

---

## 5. 后处理 Hook 链：AfterAiHookChain

**组件：** `SseResponseProcessor` → `AfterAiHookChain` → `TaskTriggerHook`

Phase 1 LLM 回复生成后执行的决策链，决定后续路由方向。

```mermaid
flowchart TD
    A["Phase 1 回复<br/>fullResponse"] --> B["AfterAiHookChain.execute()"]
    B --> C{"TaskTriggerHook<br/>触发词检测"}
    C -->|输入含触发词<br/>且回复有效| D["PLANNING 🗺️<br/>需要拆解执行"]
    C -->|不命中| E["PASS ✅<br/>直接返回回复"]
    C -->|回复含"无法/抱歉"| E
    D --> F["聚合决策<br/>PLANNING 具有传染性"]

    style D fill:#fce4ec
    style E fill:#c8e6c9
```

### TaskTriggerHook 触发词表

```
对比、总结、分析、统计、归纳、报告、
比较、差异、变化、趋势、分别、
查、查询、天气、看看、找一下
```

**跳过条件（不进入 PLANNING）：**
- AI 回复 < 5 字
- AI 回复包含"无法"、"不能"、"抱歉"

### AfterAiHookChain 优先级短路

```
BLOCK > REPLACE > PLANNING > PASS
```

多个 Hook 返回 PLANNING 时具有**传染性**——只要一个认为需要拆解，就进 TaskPlanner。

---

## 6. 响应路由器：AiResponseRouter

**组件：** `AiResponseRouter`

根据 AfterAiHookChain 的决策结果，分发到对应处理器。

```mermaid
flowchart TD
    A["HookResult"] --> B{"Decision?"}
    B -->|"BLOCK"| C["推送错误消息 → SSE complete"]
    B -->|"REPLACE"| D["推送替换文本 → SSE complete"]
    B -->|"PASS"| E["推送原始回复 → SSE complete<br/>（SSE 流式已推送则跳过）"]
    B -->|"PLANNING"| F["委托 TaskPlanner<br/>异步规划执行"]

    style C fill:#ffcdd2
    style F fill:#e8f5e9
```

---

## 7. Phase 2：TaskPlanner 规划执行

**组件：** `TaskPlanner`（编排门面）→ `PlanLoopExecutor`（主循环）

Phase 2 是框架最核心的自定义编排层。当 AfterAiHookChain 决策为 `PLANNING` 时进入。

### 7.1 异步入口与主循环

```mermaid
flowchart TD
    A["TaskPlanner.planAndExecuteAsync()<br/>CompletableFuture.runAsync(subtaskExecutor)"] --> B["resume 根 span（跨线程传播）"]
    B --> C["PlanLoopExecutor.planAndExecute()"]
    C --> D["for round = 1..MAX_ROUNDS(5)"]

    D --> E["📐 decompose() 规划拆解"]
    E -->|"空计划（无工具需执行）"| F["返回 currentResponse ✅"]
    E -->|"产出 SubTask 列表"| G["SSE 推送规划阶段进度"]
    G --> H{"feature.subagent.enabled?"}

    H -->|true 子 Agent| I["SubAgentRoundExecutor.execute()"]
    H -->|false 回退| J["FallbackRoundExecutor.execute()"]

    I --> K{"遇到 ConfirmRequiredException?"}
    J --> K
    K -->|是| L["ConfirmFlowManager.pause()<br/>保存快照 → 建审批记录 → 推确认事件"]
    K -->|否| M["更新 currentResponse = 执行结果摘要"]
    M --> D

    L --> N["SSE 流暂停，等待用户确认"]
    D -->|"下一轮 decompose 空计划"| F

    style A fill:#e3f2fd
    style F fill:#c8e6c9
    style L fill:#fff9c4
```

### 7.2 decompose() — 规划拆解

**组件：** `PlanRouter`（策略接口）→ `TreePlanRouter`（意图树路由）

```mermaid
flowchart TD
    A["PlanRouter.plan(PlanRequest)"] --> B["构建工具目录<br/>TreeCatalogBuilder 按用户输入剪枝"]
    B --> C["规划 LLM 调用<br/>LLM 看到相关工具子集 → 返回 JSON 计划"]
    C --> D["PlanParser 解析 JSON"]
    D --> E["PlanValidator 三层校验"]

    E --> F{"① JSON 语法合法？"}
    F -->|否| G["丢弃，标记 invalid"]
    F -->|是| H{"② 工具名存在？"}
    H -->|否| G
    H -->|是| I{"③ 工具已完成/失败？"}
    I -->|是| G
    I -->|否| J["构建 TOOL_CALL SubTask ✅"]

    G --> K["过滤后有效任务列表"]
    J --> K

    style G fill:#ffcdd2
    style J fill:#c8e6c9
```

**按需加载工具提示词**（规划工具路由）：
- `ToolIntentTree`：三层次级意图树（查询/写操作 → 业务类别 → 工具叶子）
- `TreeCatalogBuilder`：按用户输入剪枝，LLM 只看到相关工具子集
- 空命中直接跳过规划（不回归全量）

### 7.3 SubTask 类型

| 类型 | 用途 | 执行方式 |
|------|------|---------|
| `TOOL_CALL` | 调用 `@Tool` 方法获取数据 | 匹配 ToolCallback → GuardedToolCallback → @Tool 方法 |
| `LLM_REASON` | 基于工具结果做聚合推理（回退路径） | 调 ChatClient 生成结论 |

---

## 8. 子 Agent 执行路径（SubAgentRoundExecutor）

**组件：** `SubAgentRoundExecutor` → `SubTaskAgent` → `SubAgentToolLoop` 策略

子 Agent 路径（`feature.subagent.enabled=true`，默认开启）是主要的工具执行路径。

```mermaid
flowchart TD
    A["SubAgentRoundExecutor.execute()"] --> B["构建 SubTaskPlan<br/>（输入 + 任务列表 + 历史摘要 + 用户信息）"]
    B --> C["构建 SubTaskExecution<br/>（Plan + Callback + Properties）"]
    C --> D["SubTaskAgent.execute()"]

    D --> E["按 tasks 筛选 ToolCallback<br/>（同名去重，只保留计划内工具）"]
    E --> F["渲染执行 Prompt<br/>PromptService.render(SUBAGENT_EXECUTION)"]
    F --> G["渲染系统提示词<br/>PromptService.render(SYSTEM_SUBAGENT)"]

    G --> H["SubAgentRetryRunner.executeWithRetry()"]
    H --> I["SubAgentToolLoop.execute(ctx)<br/>策略：Serial / Batch"]

    I --> J{"工具循环：<br/>LLM 返回 tool_call?"}
    J -->|"有 tool_call"| K["ToolGuardGate 守卫评估"]
    J -->|"无 tool_call<br/>纯文本回复"| L["循环结束，返回回复"]

    K --> M{"Decision?"}
    M -->|ALLOW| N["ToolCallExecutor 执行工具"]
    M -->|BLOCK| O["返回拦截消息给 LLM"]
    M -->|CONFIRM| P["抛出 ConfirmRequiredException"]

    N --> Q["工具结果"]
    Q -->|"结果 > compressLength"| R["ToolResultCompressor 压缩<br/>（LLM 摘要）"]
    Q -->|"结果 ≤ compressLength"| S["直接返回原文"]
    R --> T["结果追加到消息历史"]
    S --> T
    T --> J

    L --> U["SubTaskResultParser 解析<br/>提取 JSON 数据快照 + 截断"]
    U --> V["返回 SubTaskResult<br/>（summary + rawResults）"]

    style K fill:#fff3e0
    style P fill:#fff9c4
    style V fill:#c8e6c9
```

### 8.1 SubAgentToolLoop 策略

| 策略 | 配置值 | 行为 |
|------|--------|------|
| **SerialToolLoop** | `serial`（默认） | 每次只调一个工具，等待返回后再调下一个 |
| **BatchToolLoop** | `batch` | 一轮可发多个独立工具调用 + 轮内并发执行 |

**策略接口 `SubAgentToolLoop`：**
- `execute(ctx)` — 完整驱动一次子 Agent 工具循环
- `toolCallRule()` — 返回 prompt 规则文本，注入 `{{toolCallRule}}` 占位符

**每轮重渲染执行 prompt**（优化点）：
- `remaining` 缩到未执行任务
- `doneSummary` 回填已完成工具的压缩摘要
- 让每轮上下文如实反映执行进度

### 8.2 工具结果压缩（ToolResultCompressor）

当工具返回结果超过 `compressLength`（默认 80 字符）时，调用 LLM 生成摘要：

```
原始结果（2000 token） → LLM 压缩 → 摘要（~200 token）
```

短结果直接返回原文，不调用 LLM。

---

## 9. 工具调用守卫系统

**组件：** `GuardedToolCallback` → `ToolGuardGate` → `ToolGuardManager` → 各 `ToolGuardPolicy`

每次工具调用前经过守卫评估，决策为 ALLOW / BLOCK / CONFIRM。

```mermaid
flowchart TD
    A["LLM 发起 tool_call"] --> B["GuardedToolCallback.call()"]
    B --> C["构建 ToolInvocationContext<br/>（toolName + arguments + userId + conversationId）"]
    C --> D["ToolGuardGate.run()"]

    D --> E["ToolGuardManager.evaluate()<br/>遍历所有 ToolGuardPolicy"]

    E --> F{"HighRiskListPolicy<br/>高危工具精确匹配"}
    F -->|BLOCK| G["一票否决 → BLOCK ❌"]
    F -->|ABSTAIN| H{"ConfirmToolPolicy<br/>需确认工具列表"}
    H -->|CONFIRM| I["需确认 → CONFIRM ⚠️"]
    H -->|ABSTAIN| J{"PatternMatchPolicy<br/>正则匹配拦截"}
    J -->|BLOCK| G
    J -->|ABSTAIN| K{"RateLimitPolicy<br/>Redis 频率限制"}
    K -->|BLOCK| G
    K -->|ABSTAIN| L["全部放行 → ALLOW ✅"]

    G --> M["返回拦截消息"]
    I --> N{"approvalEnabled?"}
    N -->|是| O["抛出 ConfirmRequiredException<br/>→ TaskPlanner 暂停"]
    N -->|否| P["返回确认提示 JSON<br/>→ LLM 自行处理"]
    L --> Q["ToolCallExecutor.execute()<br/>占位符解析 + 委托调用 + 结果限长"]
    Q --> R["返回工具结果"]

    style G fill:#ffcdd2
    style I fill:#fff9c4
    style L fill:#c8e6c9
```

### 4 个守卫策略

| 策略 | 判断依据 | 决策 |
|------|---------|------|
| `HighRiskListPolicy` | YAML `block-tools` 精确匹配工具名 | BLOCK |
| `ConfirmToolPolicy` | YAML `confirm-tools` 精确匹配工具名 | CONFIRM |
| `PatternMatchPolicy` | 正则匹配工具名和参数 | BLOCK |
| `RateLimitPolicy` | Redis 计数器，每会话速率限制 | BLOCK |

**决策聚合规则：**
- 任一 BLOCK → 一票否决，直接 BLOCK
- 无 BLOCK，任一 CONFIRM → 需确认
- 全部 ABSTAIN / ALLOW → 放行

---

## 10. CONFIRM 审批流

**组件：** `ConfirmFlowManager` → `ApprovalService` → `ConfirmResumeService`

当守卫决策为 CONFIRM 且审批开启时，触发暂停-审批-恢复流程。

```mermaid
sequenceDiagram
    participant SA as SubTaskAgent
    participant Gate as ToolGuardGate
    participant CFM as ConfirmFlowManager
    participant AS as ApprovalService
    participant FE as 前端 SSE
    participant UC as 用户确认
    participant CRS as ConfirmResumeService
    participant TP as TaskPlanner

    SA->>Gate: tool_call 被 CONFIRM
    Gate->>Gate: 抛出 ConfirmRequiredException
    SA-->>CFM: 异常传播到 PlanLoopExecutor
    CFM->>CFM: 保存 TaskSnapshot 快照<br/>（输入 + 历史 + 当前回复 + 待审批工具）
    CFM->>AS: 创建审批记录（pending）
    CFM->>FE: SSE 推送 confirmEvent
    CFM-->>SA: SSE 流暂停

    UC->>FE: POST /agent/confirm (accept: text/event-stream)
    FE->>CRS: resume(approval, userId)
    CRS->>AS: markApproved() 原子 CAS
    CRS->>CRS: 重建 AgentContext + SseEmitter
    CRS->>TP: resumeFromSnapshot(snapshot, ctx, emitter)
    TP->>TP: ① callBypass 执行待审批工具<br/>② seedCompletedHistory 预置历史<br/>③ planLoopExecutor.planAndExecute() 续跑
    TP->>FE: SSE 续流推送最终结论
```

**快照内容（TaskSnapshot）：**
- `originalInput` — 原始用户输入
- `currentResponse` — 暂停前的部分回复
- `history` — 已完成的 TaskReport
- `pendingToolName` / `pendingPayload` — 待审批的工具调用
- `rootSpan` — 观测根 span（断链修复）

---

## 11. 历史记录

**组件：** `HistoryRecorder`（Phase 1 落库）/ `AgentHistoryService`（Phase 2 落库）

| 路径 | 落库时机 | 组件 |
|------|---------|------|
| JSON 模式 | AI 回复成功后 | `HistoryRecorder.recordBestEffort()` |
| SSE Phase 1（PASS/REPLACE） | AfterAiHook 决策后 | `HistoryRecorder.recordBestEffort()` |
| SSE Phase 2（PLANNING） | TaskPlanner 完成完整回合 | `AgentHistoryService.recordTurn()` |
| CONFIRM 暂停 | 不落库（回合未完成） | — |

存储介质：`ChatMemory`（JDBC 持久化，`MessageWindowChatMemory`，最近 10 轮）。

---

## 12. 组件全景图

```mermaid
flowchart TB
    subgraph "入口层"
        CC["ChatController<br/>/agent/string/send"]
    end

    subgraph "上下文层"
        AC["AgentContext<br/>请求级上下文载体"]
        ACH["AgentContextHolder<br/>ThreadLocal 持有者"]
        ACP["AgentContextPropagator<br/>异步边界传播"]
    end

    subgraph "前置拦截层"
        PHC["PromptHookExecutor<br/>Hook 链执行器"]
        PHChain["PromptHookChain<br/>串行链"]
        IDH["InjectionDetectHook<br/>注入检测"]
        SWH["SensitiveWordHook<br/>敏感词脱敏"]
    end

    subgraph "Phase 1 — 纯文本 LLM"
        SCI["StreamingChatInvoker<br/>流式调用 + 重试"]
        PSS["PromptService<br/>提示词渲染（Langfuse + 内置兜底）"]
    end

    subgraph "后处理决策层"
        SRP["SseResponseProcessor<br/>后处理编排"]
        AAHC["AfterAiHookChain<br/>后处理 Hook 链"]
        TTH["TaskTriggerHook<br/>触发词检测"]
        ARR["AiResponseRouter<br/>响应路由"]
    end

    subgraph "Phase 2 — 规划执行"
        TP["TaskPlanner<br/>编排门面"]
        PLE["PlanLoopExecutor<br/>主循环（最多5轮）"]
        PR["PlanRouter / TreePlanRouter<br/>规划策略"]
        PP["PlanParser + PlanValidator<br/>解析校验"]
    end

    subgraph "子 Agent 执行"
        SARE["SubAgentRoundExecutor<br/>子 Agent 分支"]
        STA["SubTaskAgent<br/>子任务执行 Agent"]
        SATL["SubAgentToolLoop<br/>工具循环策略（Serial/Batch）"]
        TRC["ToolResultCompressor<br/>结果压缩"]
    end

    subgraph "回退执行"
        FRE["FallbackRoundExecutor<br/>串行回退分支"]
        TE["TaskExecutor<br/>原串行执行器"]
    end

    subgraph "工具守卫层"
        GTC["GuardedToolCallback<br/>ToolCallback 代理"]
        TGG["ToolGuardGate<br/>决策门"]
        TGM["ToolGuardManager<br/>策略聚合"]
        P1["HighRiskListPolicy"]
        P2["ConfirmToolPolicy"]
        P3["PatternMatchPolicy"]
        P4["RateLimitPolicy"]
        TCE["ToolCallExecutor<br/>执行小步"]
    end

    subgraph "审批流"
        CFM["ConfirmFlowManager<br/>暂停/快照/审批"]
        CRS["ConfirmResumeService<br/>恢复执行"]
    end

    subgraph "工具层"
        TBC["ToolBeanCollector<br/>@TargetTool 自动扫描"]
        BT["BlogTool / ShopQueryTool<br/>WeatherQueryTool / StatsQueryTool ..."]
    end

    subgraph "权限层"
        TPA["ToolPermissionAspect<br/>AOP 切面"]
        PVF["PermissionValidatorFactory<br/>校验器工厂"]
    end

    subgraph "历史层"
        HR["HistoryRecorder<br/>Phase 1 落库"]
        AHS["AgentHistoryService<br/>Phase 2 落库"]
    end

    CC --> PHC
    CC --> AC
    AC --> ACH
    PHC --> PHChain
    PHChain --> IDH
    PHChain --> SWH
    PHC --> SCI
    PSS --> SCI
    SCI --> SRP
    SRP --> AAHC
    AAHC --> TTH
    SRP --> ARR
    ARR --> TP
    TP --> PLE
    PLE --> PR
    PR --> PP
    PLE --> SARE
    PLE --> FRE
    SARE --> STA
    STA --> SATL
    SATL --> TRC
    SATL --> GTC
    FRE --> TE
    TE --> GTC
    GTC --> TGG
    TGG --> TGM
    TGM --> P1
    TGM --> P2
    TGM --> P3
    TGM --> P4
    TGG --> TCE
    TCE --> TPA
    TPA --> PVF
    PVF --> BT
    TBC --> BT
    TP --> CFM
    CFM --> CRS
    SRP --> HR
    TP --> AHS
```

---

## 13. 端到端时序总结

以 **"查看我的博客以及长沙天气"** 为例：

```mermaid
sequenceDiagram
    participant U as 用户
    participant CC as ChatController
    participant Hook as PromptHookChain
    participant LLM1 as Phase 1 LLM
    participant AfterHook as AfterAiHookChain
    participant Router as AiResponseRouter
    participant Planner as TaskPlanner
    participant PlanLLM as 规划 LLM
    participant SubAgent as SubTaskAgent
    participant Guard as GuardedToolCallback
    participant Tool1 as queryPublishedBlogs
    participant Tool2 as queryWeather

    U->>CC: "查看我的博客以及长沙天气"
    CC->>Hook: 前置 Hook 链
    Hook-->>CC: PASS（无注入/敏感词）
    CC->>LLM1: 纯文本调用（无工具）
    LLM1-->>CC: "我来查一下"

    CC->>AfterHook: TaskTriggerHook 检测
    AfterHook-->>CC: PLANNING（"查" 命中触发词）

    CC->>Router: route(PLANNING)
    Router->>Planner: planAndExecuteAsync()

    Note over Planner: Round 1
    Planner->>PlanLLM: decompose() 规划拆解
    PlanLLM-->>Planner: [queryPublishedBlogs, queryWeather]

    Planner->>SubAgent: SubAgentRoundExecutor.execute()
    SubAgent->>SubAgent: 构建执行 Prompt + 筛选工具

    Note over SubAgent: 工具循环 轮1
    SubAgent->>LLM1: subagent-exec（带工具调用规则）
    LLM1-->>SubAgent: tool_call: queryPublishedBlogs
    SubAgent->>Guard: call(queryPublishedBlogs)
    Guard-->>SubAgent: ALLOW
    SubAgent->>Tool1: 执行查询
    Tool1-->>SubAgent: 博客列表数据

    Note over SubAgent: 结果压缩（如超长）
    SubAgent->>SubAgent: compress(博客结果) → 摘要

    Note over SubAgent: 工具循环 轮2
    SubAgent->>LLM1: subagent-exec（带已执行摘要）
    LLM1-->>SubAgent: tool_call: queryWeather
    SubAgent->>Guard: call(queryWeather)
    Guard-->>SubAgent: ALLOW
    SubAgent->>Tool2: 执行查询
    Tool2-->>SubAgent: "长沙晴天 25°C"

    Note over SubAgent: 工具循环 轮3
    SubAgent->>LLM1: subagent-exec（所有工具已完成）
    LLM1-->>SubAgent: 最终回答（含 JSON 快照）

    SubAgent-->>Planner: SubTaskResult（summary + rawResults）

    Note over Planner: Round 2
    Planner->>PlanLLM: decompose() → 空计划
    Planner-->>U: 推送最终结论
```

---

## 附：关键配置项

```yaml
agent:
  prompt:
    enabled: true                    # 提示词外置开关
    base-url: ${LANGFUSE_BASE_URL:}  # Langfuse 远程（空则走内置）
    cache-ttl: 5m                    # 提示词缓存 TTL

  subtask:
    tool-loop: serial                # serial（默认）/ batch
    compress-length: 80              # 工具结果超此长度触发压缩

  feature:
    subagent:
      enabled: true                  # 子 Agent 路径开关
    tool-routing:
      enabled: true                  # 规划工具路由开关（按需加载）

hmdp:
  prompt-guard:
    block-tools:                     # 高危工具拦截名单
      - deleteBlog
    confirm-tools:                   # 需确认工具名单
      - publishTestBlog
    rate-limit:
      max-per-session: 30
      window-seconds: 60
```
