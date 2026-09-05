# PickNear Agent — 基于 Spring AI 的企业级 LLM Agent 框架

> **版本**: v3.1  
> **技术栈**: Spring Boot 3.4.4 · Spring AI 1.1.2 · DashScope (通义千问) · Java 17  
> **定位**: 在 Spring AI 基础上构建的**生产级** Agent 框架，面向企业 Java 团队

---

## 目录

- [一句话概括](#一句话概括)
- [核心特性](#核心特性)
- [架构总览](#架构总览)
- [与知名框架对比](#与知名框架对比)
- [优点](#优点)
- [缺点与局限](#缺点与局限)
- [适用场景](#适用场景)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [扩展指南](#扩展指南)
- [文档索引](#文档索引)

---

## 一句话概括

**在 Spring AI 之上，用两阶段规划 + 路由策略化 + 多层安全守卫 + 多后端可观测，把 LLM Agent 从"能跑"推到"能上线"。**

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **两阶段规划执行** | Phase 1 纯文本回复 → AfterAiHook 决策 → Phase 2 TaskPlanner 提交，MultiRoundOrchestrator 多轮编排（decompose → execute → merge，最多 5 轮收敛），避免工具重复执行 |
| **规划路由策略化** | PlanRouter 策略接口：默认 TreePlanRouter 意图→工具组两级路由（ToolIntentTree 关键词剪枝 + 两段式规划 prompt + PlanParser/PlanValidator 四层校验），`feature.tool-routing.enabled=false` 时回退 LegacyPlanRouter |
| **工具循环策略化** | `agent.subtask.tool-loop` 一行配置切换：serial 串行（默认）/ batch 批量并发 / hybrid 混合 DAG（@DependsOn 声明依赖，无依赖并行、有依赖分层串行，失败降级串行） |
| **多层安全守卫** | PromptHookChain 前置拦截 + ToolGuardManager 多策略投票 + @RequiredDataPermission AOP 数据权限 |
| **SSE 真流式 + 阶段进度** | 直连 DashScopeChatModel.stream() 逐 token 推送；事件类型 meta/progress/error/confirm，progress 内 stage 五阶段（planning/executing/merging/step/confirm）实时展示规划/执行/汇总进度，SseSessionFactory 统一装配 |
| **子 Agent 执行链** | RoundExecutionProxy → ToolExecutionFacade（工具白名单筛选 → RetryRunner 指数退避重试 → ResultParser 快照解析），带幂等性智能重试（@ToolMeta.idempotent） |
| **工具注册单一来源** | @TargetTool 自动扫描 + @ToolMeta 注解（触发词 keywords / 意图树归属 intents / 幂等重试）→ ToolRegistry 启动聚合，过滤/路由/种子自动感知 |
| **CONFIRM 审批流** | 敏感工具调用前真暂停（ConfirmRequiredException 穿透），agent_approval 表持久化快照，`/agent/confirm` 审批通过后从快照续流恢复（CAS 状态机 + 超时清扫） |
| **全链路可观测** | AgentTracer 业务埋点 → OTel → 多后端可插拔（langfuse / jaeger / signoz / collector / console / noop），session → phase1 → round → subagent → tool_call → guard 多层 span 同 traceId 串树 |
| **提示词工程化** | Langfuse Prompt Management + 内置模板三级降级，运行期热改（/agent/prompt/reload），工具描述外置，PromptRepository 策略化（langfuse / none） |
| **多轮对话记忆（已接通）** | agent_conversation / agent_message 双表落库完整历史；SSE 请求回放最近 N 轮（P1 回放）+ 长会话后台把旧轮异步压成运行摘要（P2 压缩），详见《上下文压缩子系统设计文档》 |
| **对话流式（SSE-only）** | 对话统一 SSE 流式推送；JSON 同步模式已废弃（2026-09-03） |
| **请求级上下文** | AgentContext + ThreadLocal + TaskDecorator 跨线程传播（userId / conversationId / 原始输入 / 根 span），取代散落的旧载体 |
| **可插拔架构** | @TargetTool + @ToolMeta 注解自动注册，Guard / Hook / Router / ToolLoop 均为策略接口，新增组件只加注解或实现类 |

---

## 架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                       前端（Vue 3）                               │
└────────────────────────────┬─────────────────────────────────────┘
                             │ HTTP / SSE
┌────────────────────────────▼─────────────────────────────────────┐
│  ChatController（/agent/string/send 流式 · /agent/confirm 审批）   │
│  ├─ SseSessionFactory        ← SSE 会话统一装配（根 span/超时/常量）│
│  ├─ AgentContext             ← 请求级上下文（ThreadLocal + 传播）  │
│  ├─ PromptHookExecutor       ← Hook 链执行 + 决策                 │
│  │   ├─ InjectionDetectHook  │   Prompt 注入检测                  │
│  │   └─ SensitiveWordHook    │   敏感词脱敏                       │
│  ├─ StreamingChatInvoker     ← 流式调用 + 3 次重试                │
│  └─ SseResponseProcessor     ← AfterAiHook → 路由 → 历史落库      │
├──────────────────────────────┬───────────────────────────────────┤
│  TaskPlanner（编排门面）→ MultiRoundOrchestrator（主循环 ≤5 轮）   │
│  ├─ PlanRouter 策略          ← TreePlanRouter 两级路由 / Legacy   │
│  │   ├─ ToolIntentTree       │   意图树匹配 + 剪枝目录             │
│  │   ├─ PlanParser/Validator │   四层校验（存在/历史/归属/占位符） │
│  │   └─ LegacyPlanRouter     │   紧凑目录 + UNCERTAIN 全量重跑    │
│  ├─ RoundExecutionProxy      ← 子 Agent 轮执行（观测 + 历史）      │
│  │   └─ ToolExecutionFacade  │   filterCallbacks → RetryRunner    │
│  │       └─ ToolLoop 策略    │   → ResultParser（===DATA_SNAPSHOT）│
│  │           ├─ SerialStrategy     serial：按轮逐个                │
│  │           ├─ ParallelStrategy   batch：批量 + 压缩并发          │
│  │           └─ DagStrategy        hybrid：@DependsOn 分层 DAG     │
│  └─ ConfirmFlowManager       ← CONFIRM 暂停（快照+审批）/ 续流恢复 │
│      └─ FallbackRoundExecutor（feature.subagent.enabled=false）   │
├──────────────────────────────┬───────────────────────────────────┤
│  Guard 层（工具调用守卫）                                          │
│  GuardedToolCallback → ToolGuardManager → 策略投票                 │
│  ├─ HighRiskListPolicy · ConfirmToolPolicy                       │
│  ├─ PatternMatchPolicy · RateLimitPolicy                         │
│  └─ @RequiredDataPermission AOP 数据权限                          │
├──────────────────────────────┬───────────────────────────────────┤
│  观测：AgentTracer → OTel → 多后端（langfuse/jaeger/signoz/…）     │
│  ObservedSseEmitter 收敛会话根 span（五路径 → root.end）           │
├──────────────────────────────┴───────────────────────────────────┤
│  Spring AI SDK + DashScope（MaaS OpenAI 兼容端点）                 │
└──────────────────────────────────────────────────────────────────┘
```

---

## 与知名框架对比

### 与 Spring AI 的关系

本框架**基于** Spring AI 1.1.2 构建，不是替代品，而是在其之上补充了企业级能力：

| 维度 | Spring AI 原生 | 本框架扩展 |
|------|---------------|-----------|
| 工具注册 | 手动 `.defaultToolCallbacks()` | `@TargetTool` 扫描 + `@ToolMeta` 元数据 + `ToolRegistry` 单一来源 |
| 安全防护 | 无 | 三层防护（Hook + Guard + Permission） |
| 任务规划 | 无 | 两阶段 Plan-and-Execute + 路由策略化 + 工具循环策略化（serial/batch/DAG） |
| 可观测性 | Micrometer Observation | 多后端可插拔 OTLP（Langfuse/jaeger/…）全链路追踪 |
| 提示词管理 | 硬编码 | Langfuse 云 + 内置模板三级降级 |
| 审批流 | 无 | CONFIRM 真暂停 + 快照恢复 |

### 与 LangChain / LangChain4j 对比

| 维度 | LangChain4j | 本框架 |
|------|-------------|--------|
| 定位 | 通用 LLM 应用开发框架 | 企业级 Java Agent 框架 |
| 学习曲线 | 平缓（`@AiService` 注解即用） | 陡峭（多层架构需深入理解） |
| 灵活性 | 高（链式可自由组合） | 中（固定两阶段架构 + 策略点可插拔） |
| 安全防护 | 基础工具验证 | 多层防护体系（投票制 + AOP） |
| 规划能力 | 简单路由 | AI 规划 + Java 四层校验 + 意图树路由 |
| 社区生态 | 强（Python LangChain 衍生） | 弱（自定义框架） |
| Spring 集成 | 独立框架 | 深度 Spring Boot 集成 |

### 与 AutoGen（微软）/ CrewAI 对比

| 维度 | AutoGen / CrewAI | 本框架 |
|------|------------------|--------|
| 核心理念 | 多代理协作（角色扮演） | 单代理深度规划（工程化） |
| 语言生态 | Python | Java |
| 多代理协作 | ✅ 原生支持 | ❌ 不支持（远期规划） |
| 安全防护 | 基础验证 | 企业级多层防护 |
| 适用场景 | 研究 / 原型 / 多代理实验 | 生产环境企业应用 |

---

## 优点

### 1. 安全纵深做得扎实

三层防护不是冗余，而是各有侧重：

| 层 | 介入时机 | 判断依据 | 性能 |
|----|---------|---------|------|
| PromptHookChain | AI 调用前 | 注入特征、敏感词 | 微秒级 |
| ToolGuardManager | 工具 call() 前 | 工具名、参数、频率 | 微秒级（Redis） |
| @RequiredDataPermission | @Tool 方法前 | 数据归属权、用户身份 | 毫秒级（DB） |

Guard 层是纯无状态的（YAML + Redis + 正则），零业务 Service 依赖，即使 AOP 失效，第一层守卫仍起作用。上线至今未发生工具越权操作。

### 2. 两阶段架构避免了工具重复执行

Phase 1 根本不注册工具，AI 只做纯文本回复。只有 AfterAiHook 判定需要 PLANNING 时才进入 Phase 2。这比"第一轮 AI 就带工具"的方案：
- 工具不会重复执行
- 不需要脆弱的关键词去重
- 日志阶段清晰，便于排查

Phase 2 的工具循环本身也是策略化的——serial / batch / hybrid 一行配置切换，灰度 A/B 零成本，且每轮基于剩余任务 + 压缩摘要重渲染，token 不会滚雪球。

### 3. 可观测性做到了生产级

多后端可插拔（Langfuse 云 / jaeger / signoz / console 调试）解决了 AI 链路最大的痛点——**多异步线程下 traceId 断链**。通过字节码分析 + 实测修复了两个根因（生命周期时序 + Reactor context 污染），全链路同 traceId 串树。观测白名单（trace-filter）防止 Spring 定时任务把配额吃光。

### 4. 提示词工程化，运行期可热改

Langfuse Prompt Management 为事实源 + 内置模板兜底，改提示词不需要重编译重部署。三级 Fail-Open 降级保证 Langfuse 故障时功能不降级。工具描述也可外置，支持运行期热改。启动预热 + 双缓存（成功文本 / 404 负缓存 / 30s 熔断）。

### 5. 企业级 Spring 生态深度集成

不是独立框架，而是 Spring Boot 的一等公民。依赖注入、配置管理、事务管理、Actuator 监控全部原生集成。团队已有 Spring 技术栈的话，上手成本最低。

---

## 缺点与局限

### 1. 两阶段架构引入固有延迟

```
用户提问 → Phase 1 LLM 调用（等待） → Hook 决策 → Phase 2 规划 LLM 调用（等待） → 执行 → 合并
```

**对于简单问题（如"你好"、"今天天气"），也要等 Phase 1 的 LLM 回复，然后再等决策。** 虽然不需要工具的简单问题不进入 Phase 2，但 Phase 1 的延迟是不可避免的。总延迟 = Phase 1 LLM 延迟 + Hook 决策 + （若 PLANNING）Phase 2 规划 + 执行 + 合并。

### 2. 架构复杂度高，新人上手门槛大

框架包含 10+ 个核心组件、3 套责任链、多层代理/守卫/权限。新开发者需要理解：

- PromptHookChain / AfterAiHookChain 两套链的区别和执行规则
- PlanRouter 策略（TreePlanRouter 两级路由 vs LegacyPlanRouter）与四层校验
- MultiRoundOrchestrator 多轮循环与工具循环策略（serial/batch/hybrid）
- 子 Agent 执行链（RoundExecutionProxy → ToolExecutionFacade → RetryRunner/ResultParser）
- SSE 事件协议（meta/progress/error/confirm + stage 五阶段）
- 观测体系（span 命名、白名单、多后端、串树）

**调试一个工具调用可能需要追踪 5 个以上的类。** 这是企业级架构不可避免的代价。

### 3. 不支持多代理协作

当前是单代理深度规划模型（Plan-and-Execute），不支持：
- 多个代理之间的对话协作
- 角色扮演（Researcher / Writer / Analyst）
- 代理间的实时消息传递

这是与 AutoGen / CrewAI 最大的功能差距。远期路线图中有"多 Agent 编排"，但目前未实现。

### 4. 不是 ReAct 模式

当前采用的是 Plan-and-Execute（先规划后执行），而非 ReAct（推理-行动循环）。区别：

| 维度 | Plan-and-Execute（本框架） | ReAct |
|------|--------------------------|-------|
| 流程 | 先规划完整计划，再批量执行 | 边推理边行动，每步观察后再决定下一步 |
| 灵活性 | 计划固定，执行中不易调整 | 每步可根据中间结果动态调整 |
| LLM 调用次数 | 少（规划一次 + 合并一次） | 多（每步一次推理） |
| Token 消耗 | 低 | 高 |
| 适用场景 | 步骤明确的任务 | 需要动态探索的任务 |

**这意味着对于需要动态探索的复杂任务（如多轮搜索、条件分支），当前架构的适应性不如 ReAct。**

### 5. 自建组件多，维护成本不可忽视

以下组件都是自建的，没有社区支持：

- TaskPlanner / MultiRoundOrchestrator / PlanRouter（Tree/Legacy）
- ToolGuardManager + 4 个 Policy
- PromptHookChain + AfterAiHookChain
- ToolExecutionFacade / RetryRunner / ResultParser + 工具循环策略（Serial/Parallel/Dag）
- ToolRegistry / @ToolMeta 元数据体系
- SSE 事件协议 + SseSessionFactory / ObservedSseEmitter
- 多后端观测层 + 提示词渲染三级降级

每个组件的 bug 修复、功能迭代、文档维护都需要自己做。相比直接用 LangChain4j 的开箱即用，维护成本明显更高。

### 6. 测试覆盖有待提升

多层架构的测试复杂度高：
- 需要 Mock 多个层次的依赖
- 异步/流式测试需要特殊处理（线程池替换、CountDownLatch）
- 集成测试需要完整基础设施（Redis + MySQL + Langfuse）

当前测试覆盖约 75%，核心链路已覆盖，但边缘场景和错误路径仍有缺口。

### 7. 对话历史与上下文（2026-09-03 起对话仅 SSE）

JSON 同步对话模式已废弃（`chatReturnStringResult` 删除，`/agent/string/send` 仅 SSE）。历史与回放：
- 历史落库：自建 `agent_conversation` / `agent_message`（SSE 每回合 user+assistant 各一行）；JDBC ChatMemory（`SPRING_AI_CHAT_MEMORY`）**空转**（无 `chatMemory.add` 写入方），不是历史来源。
- **记忆回放（P1）**：`ConversationReplayService` 契约读取 agent_message 尾部 N 轮 → `[System(历史摘要，可选)] + 近期完整消息` 注入模型。
- **对话级异步压缩（P2）**：`recordTurn` 落库事务 afterCommit 发事件 → 独立 compressExecutor（小模型 qwen-flash）把旧轮异步压进 Redis `agent:conv:{cid}:mem`（Mem：summary+uptoId 单 key 原子）；回放自动升级为 `[摘要]+id>uptoId 增量`，原值永在 agent_message。压缩旁路 fail-open（dirty 自愈 + sweeper）。

---

## 适用场景

### ✅ 适合

| 场景 | 原因 |
|------|------|
| 企业级 Java 应用接入 AI 能力 | Spring 生态深度集成，安全防护完善 |
| 需要严格工具调用控制的场景 | 三层防护 + 审批流 + 数据权限 |
| 已有 Spring Boot 技术栈的团队 | 学习成本最低，复用现有基础设施 |
| 需要生产级可观测性的场景 | 多后端 OTLP 全链路追踪 |
| 提示词需要运行期热改的场景 | Langfuse Prompt Management 三级降级 |
| 工具间有依赖关系的复杂任务 | hybrid DAG 模式：无依赖并行、有依赖分层串行 |

### ❌ 不适合

| 场景 | 原因 |
|------|------|
| 快速原型 / PoC 验证 | 架构过重，开发速度慢 |
| 多代理协作 / 角色扮演 | 不支持多 Agent 通信 |
| 需要动态探索的复杂任务 | Plan-and-Execute 不如 ReAct 灵活 |
| 轻量级 / 资源受限环境 | 需要 Redis + MySQL + 观测后端 |
| Python 团队 | Java 生态，Python 无法直接使用 |
| 需要快速迭代的研究实验 | 自建组件多，维护成本高 |

---

## 技术栈

| 组件 | 选型 | 版本 |
|------|------|------|
| 基础框架 | Spring Boot | 3.4.4 |
| AI 框架 | Spring AI (OpenAI compatible) | 1.1.2 |
| 底层模型 | DashScope 通义千问 | qwen-plus-2025-07-28 |
| 对话记忆 | JDBC ChatMemory | MessageWindowChatMemory |
| 缓存 | Caffeine + Redis | 3.1.8 / 3.40.0 |
| 可观测性 | Micrometer + OTel + 多后端 | OTLP |
| 数据库 | MySQL | 8.0.33 |
| ORM | MyBatis-Plus | 3.5.12 |
| SSE | Spring SseEmitter | 内置 |

---

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6+
- DashScope API Key

### 1. 克隆项目

```bash
git clone git@github.com:vacontinuejatotired/picknear.git
cd picknear/picknear
```

### 2. 配置环境变量

```bash
# .env 文件（compose 同级，已被 .gitignore 忽略）
DASHSCOPE_API_KEY=your-api-key
LANGFUSE_BASE_URL=          # 留空则走内置模板
LANGFUSE_BASIC_AUTH=        # 留空则不启用远程观测
```

### 3. 启动

```bash
# Docker Compose（推荐）
docker compose up -d --build

# 或本地启动
mvn spring-boot:run
```

服务启动在 `http://localhost:8081`

### 4. 调用示例

```bash
# SSE 流式模式（对话已废弃 JSON 同步模式，仅此一种；content 走 form/query 参数）
curl -N -X POST http://localhost:8081/agent/string/send \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer <token>" \
  --data-urlencode "content=统计一下店铺数量"

# 触发敏感工具后审批续流（流中会先收到 type=confirm 事件）
curl -N -X POST http://localhost:8081/agent/confirm \
  -H "Accept: text/event-stream" \
  -H "Authorization: Bearer <token>" \
  --data-urlencode "confirmId=cfm_xxx"
```

---

## 配置说明

### 核心配置

```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://ws-mhs2k50uiwwwvefx.cn-beijing.maas.aliyuncs.com/compatible-mode
      chat:
        options:
          model: qwen-plus-2025-07-28

hmdp:
  ai-observability:
    backend:
      type: langfuse              # 观测后端：langfuse|jaeger|signoz|collector|console|noop
    trace-enabled: true           # 业务埋点总开关
    trace-filter:
      include-prefixes:           # 观测白名单（默认前缀 + 追加，防定时任务吃配额）
        - agent.
        - spring.ai.
        - gen_ai.
    chat-observation:
      include-content: true       # LLM 请求/回复全文写入（true|false|auto）
    span-naming:
      semantic-encoding: auto     # 语义后缀编码进 span 名（auto=跟后端能力）
  prompt-guard:
    block-tools: [deleteBlog]
    confirm-tools: [publishTestBlog]
    block-patterns:               # 正则拦截规则
      - toolName: ".*[Dd]elete.*"
    confirm-patterns:             # 正则确认规则
      - arguments: ".*admin.*"
    rate-limit:
      max-per-session: 30
      window-seconds: 60
    approval:
      enabled: true               # CONFIRM 真暂停；false 退回提示字符串
      ttl-seconds: 300            # 审批有效期（秒）
    tool-result:
      max-chars: 1200             # 工具结果回灌 LLM 前最大字符数

agent:
  subtask:
    timeout: 30s                    # 子 Agent 单次 LLM 调用超时
    total-timeout: 60s              # 整个 execute() 总超时（含重试）
    max-retries: 3                  # 最大重试次数
    retry-backoff: 1s               # 重试基础退避间隔
    max-tool-rounds: 6              # 手动工具循环最大轮数（触顶强制总结）
    max-total-calls: 10             # 单次执行总工具调用数上限（预算硬顶）
    tool-loop: batch                # 工具循环策略：serial=按轮逐个（默认）；batch=批量+并行；hybrid=混合 DAG
    parallel-tools: true            # batch 用：轮内多个工具并发执行
    parallel-compress: true         # batch 用：长结果压缩并发
    compress-length: 80             # 工具结果压缩摘要最大字符数
    dag:                            # hybrid（DAG）模式配置
      default-max-retries: 3        # 工具未声明时使用的全局默认重试次数
      default-retry-enabled: true   # 全局默认是否允许重试
      default-retry-base-delay-ms: 1000
      retry:
        strategy: exponential       # exponential / none
        enabled: true
        base-delay-ms: 1000
        retryable-errors:           # 可重试异常（工具级 @ToolMeta 优先）
          - SocketTimeoutException
          - ConnectException
          - IOException
  prompt:
    enabled: true                   # 提示词外置总开关
    repository:
      type: langfuse                # langfuse（默认）| none（无远程，走内置模板）
    base-url: ${LANGFUSE_BASE_URL:}
    basic-auth: ${LANGFUSE_BASIC_AUTH:}
    default-label: production       # 默认拉取 label
    cache-ttl: 30m                  # 成功文本 / 404 负缓存 TTL
    failure-cache-ttl: 30s          # 网络失败瞬时熔断 TTL
    timeout: 5s                     # RestClient 建连/读超时
    tool-description-enabled: true  # 工具描述外置开关
    seed-enabled: false             # 种子/清缓存端点开关

feature:
  subagent:
    enabled: true
  tool-routing:
    enabled: true                   # 意图→工具组两级路由（false 回退 legacy 紧凑目录）
    max-tag-length: 60              # 紧凑标签最大字符数
```

### 功能开关

| 开关 | 默认 | 说明 |
|------|------|------|
| `feature.subagent.enabled` | `true` | 子 Agent 执行（false 退回 TaskExecutor 串行直调） |
| `feature.tool-routing.enabled` | `true` | 意图→工具组两级路由（false 回退 legacy 紧凑目录） |
| `agent.subtask.tool-loop` | `serial` | 工具循环策略：serial / batch / hybrid（DAG） |
| `agent.prompt.enabled` | `true` | 提示词外置（false 全部走内置模板） |
| `agent.prompt.repository.type` | `langfuse` | 提示词仓库实现（none = 无远程） |
| `hmdp.ai-observability.backend.type` | `langfuse` | 观测后端（jaeger/signoz/collector/console/noop） |
| `hmdp.ai-observability.trace-enabled` | `true` | 业务埋点总开关 |
| `hmdp.prompt-guard.approval.enabled` | `true` | CONFIRM 真暂停（false 退回提示字符串当工具结果） |

---

## 扩展指南

### 添加一个新工具

1. 新建类，标注 `@TargetTool`
2. 方法上标注 `@Tool(description = "...")`
3. 标注 `@ToolMeta` 声明过滤/路由元数据（触发词 + 意图树归属 + 幂等重试）
4. 敏感操作加 `@RequiredDataPermission`
5. 可选：标注 `@DependsOn` 声明工具依赖（hybrid DAG 模式用），依赖参数用 `@FromTool` 显式指定来源
6. 重启应用 → `ToolBeanCollector` 自动扫描，`ToolRegistry` 聚合注册

```java
@TargetTool
public class ShopQueryTool {

    @Tool(description = "按类型查询店铺列表")
    @ToolMeta(keywords = {"店铺", "商家"}, intents = {"shop"})
    @RequiredDataPermission(resource = "shop", action = DataAction.READ)
    public List<ShopVO> queryShopsByType(
            @ToolParam(description = "店铺类型ID") Long typeId,
            ToolContext toolContext) {
        Long userId = (Long) toolContext.getToolContext().get("userId");
        // ...
    }
}
```

带依赖的工具（hybrid DAG 模式）：

```java
@TargetTool
public class ItineraryTool {

    // 依赖 queryWeather / queryBlog 的返回结果，无依赖部分并行执行
    @Tool(description = "根据天气和博客生成行程推荐")
    @ToolMeta(keywords = {"行程", "推荐"}, intents = {"itinerary"})
    @DependsOn(toolName = {"queryWeather", "queryBlog"})
    public String generateItinerary(
            String city,                    // Agent 参数（基本类型）
            @FromTool("queryWeather") WeatherResult weather,  // 依赖工具结果
            @FromTool("queryBlog") BlogResult blog) {
        // ...
    }
}
```

### 添加一个 Guard 策略

1. 实现 `ToolGuardPolicy` 接口
2. 标注 `@Component`
3. `ToolGuardManager` 自动收集

```java
@Component
public class MyCustomPolicy implements ToolGuardPolicy {
    @Override
    public String policyName() { return "my-custom"; }

    @Override
    public Vote vote(ToolInvocationContext context) {
        // ALLOW / BLOCK / CONFIRM / ABSTAIN
        return Vote.ABSTAIN;
    }
}
```

### 切换工具循环策略 / 观测后端

```yaml
agent:
  subtask:
    tool-loop: hybrid        # serial / batch / hybrid（DAG）
hmdp:
  ai-observability:
    backend:
      type: jaeger           # langfuse / jaeger / signoz / collector / console / noop
```

---

## 文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| 架构设计 | `Agent模块架构设计.md` | 完整架构图、层叠结构、数据流、设计决策 |
| 设计模式 | `Agent模块设计模式.md` | 11 种设计模式在框架中的应用 |
| 简历亮点 | `Agent模块简历亮点.md` | 7 个技术难点攻克的工程叙事 |
| 发展路线图 | `Agent模块发展路线图.md` | Phase 0-5 落地状态、遗留项 |
| 链路迭代 | `Agent模块链路迭代文档.md` | 从 v1 到 v3 的演进历史（Phase 0-23） |
| 上下文传递 | `Agent上下文传递机制设计.md` | AgentContext 请求级上下文设计 |
| DAG 规划 | `DAG规划执行器设计文档.md` | DAG 执行计划、依赖解析、分层并行 |
| 规划路由 | `规划工具路由设计.md` | 意图树、两级路由、目录构建 |
| 子 Agent 执行 | `SubTaskAgent子Agent执行方案.md` | 子 Agent 循环、工具筛选、结果压缩 |
| 反编造机制 | `Agent反编造机制设计文档.md` | 幻觉治理：证据源捕获、输入侧路由、输出断言闸、事实账本（P0-P2 分阶段） |
| 审批方案 | `AgentCONFIRM审批方案.md` | CONFIRM 真暂停 + 快照恢复设计 |
| SSE 规范 | `SSE后端实现规范.md` | SSE 事件协议、ObservedSseEmitter |
| 观测架构 | `observability/Agent全链路观测架构设计.md` | 观测 span 串树、多后端、配额管理 |
| Langfuse 接入 | `observability/Langfuse云接入说明.md` | Langfuse 云 OTLP 接入说明 |

---

## License

Internal use only.
