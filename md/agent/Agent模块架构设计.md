# Agent 模块架构设计

> **版本**: v2.0  
> **最后更新**: 2026-07-24  
> **对应代码路径**: `picknear/src/main/java/com/hmdp/` 下的 `agent/`, `permission/`, `aspect/`, `promptguard/`, `prompthook/`, `exception/`  
> **相关文档**: [Agent任务队列方案](./Agent任务队列方案.md), [Agent模块简历亮点](./Agent模块简历亮点.md), [SSE后端实现规范](./SSE后端实现规范.md)

---

## 目录

1. [模块定位](#1-模块定位)
2. [整体架构](#2-整体架构)
3. [层叠结构详解](#3-层叠结构详解)
4. [核心数据流](#4-核心数据流)
5. [关键设计决策](#5-关键设计决策)
6. [扩展指南](#6-扩展指南)
7. [配置说明](#7-配置说明)
8. [监控与日志](#8-监控与日志)

---

## 1. 模块定位

Agent 模块是 picknear 的"智能层"，通过大语言模型（LLM）为用户提供自然语言驱动的交互体验。

### 1.1 核心能力

| 能力 | 说明 | 状态 |
|------|------|------|
| **自然语言对话** | 用户用中文提问，AI 理解意图并回复 | ✅ 已实现 |
| **工具调用（Function Calling）** | AI 规划后执行后端工具（查天气、查博客、发布博客） | ✅ 已实现 |
| **双模响应** | 同一端点同时支持 JSON 同步响应 和 SSE 流式推送 | ✅ 已实现 |
| **多轮对话记忆** | 通过 ChatMemory 保留最近 10 轮上下文 | ✅ 已实现 |
| **权限校验** | AOP 切面 + 策略模式校验器，可插拔 | ✅ 已实现 |
| **上下文安全** | 通过 `toolContext` 将当前用户 ID 注入工具调用 | ✅ 已实现 |
| **审批授权** | 敏感工具调用前需用户确认 | 📅 预留 |

### 1.2 技术栈

| 组件 | 选型 | 版本 |
|------|------|------|
| AI 框架 | Spring AI（Alibaba DashScope 适配） | 1.1.2 |
| 底层模型 | DashScope（通义千问） | qwen-plus-2025-07-28 |
| SSE 容器 | Spring `SseEmitter` | 内置于 Spring Web |
| 工具注册 | 自定义注解 `@TargetTool` + 自动扫描 | — |
| 对话记忆 | JDBC 持久化 `MessageWindowChatMemory` | — |
| HTTP 连接池 | Apache HttpClient 5（同步） + Reactor Netty（流式） | — |

---

## 2. 整体架构

### 2.1 两阶段架构

Agent 模块采用**两阶段设计**，核心思想是**先规划后执行**：

```
Phase 1: AI 纯文本回复（不绑工具）
    → AfterAiHookChain 决策
      ├─ PASS     → 直接返回
      ├─ BLOCK    → 返回错误
      └─ PLANNING → Phase 2

Phase 2: TaskPlanner 规划执行
    → decompose() —— AI 规划 + Java 三层校验
    → executeAll() — 串行执行 TOOL_CALL
    → merge()      —— LLM_REASON 聚合结论
    → 最多 5 轮
```

相比"第一轮 AI 就带工具"的方案，两阶段的核心收益：
- 工具不会重复执行（Phase 1 根本未注册工具）
- 避免去重机制（不再需要脆弱的 `markAiCompletedTools` 关键词匹配）
- 日志阶段清晰，便于排查

### 2.2 分层结构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         前端（Vue 3）                                    │
│  AiChat.vue → src/api/agent.ts → axios / fetch                          │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ HTTP / SSE
                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Controller 层                                       │
│  ChatController                                                         │
│  └─ POST /agent/string/send  ← Accept 头协商 → JSON or SSE             │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────────────┐
│                      Service 层（编排入口）                               │
│  AiService (接口) / AiServiceImpl (实现)                                 │
│  ├─ chatReturnStringResult()  — 同步模式                                │
│  └─ chatWithToolcall()        — SSE + 两阶段架构                        │
│       ├─ Phase 1: 纯文本 AI 调用（无工具，3 次重试 + 喂错）              │
│       └─ AfterAiHookChain → AiResponseRouter 路由                      │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────────────┐
│                    AfterAiHook 后处理层（只做判断）                       │
│  AfterAiHookChain（链式执行器，PLANNING 传染性）                         │
│  └─ TaskTriggerHook（触发词匹配："统计""分析""对比"等）                  │
│  └─ AiResponseRouter（路由：BLOCK/REPLACE/PASS/PLANNING）               │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ PLANNING
                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  TaskPlanner 规划执行层                                   │
│  planAndExecuteAsync() → 异步循环：                                      │
│  ├─ decompose() —— AI 规划 + Java 三层校验                              │
│  │   askAiForPlan() → validatePlan()（JSON→工具存在性→历史去重）          │
│  │   校验通过 → 构建 SubTask 列表，强制追加 LLM_REASON                   │
│  │   空计划 → 兜底退出                                                 │
│  ├─ executeAll() —— TaskExecutor 串行执行                               │
│  │   TOOL_CALL → GuardedToolCallback → @Tool 方法                      │
│  │   LLM_REASON → ChatClient 聚合                                       │
│  └─ merge() —— 取 LLM_REASON 结论                                       │
│  SseUtils: progressEvent/stepEvent/confirmEvent SSE 推送                │
└─────────────────────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────────────┐
│                     Tool 层（工具函数）                                   │
│  ToolBeanCollector (自动收集 + GuardedToolCallback 包装)                 │
│  ├─ BlogTool           — 博客查询 / 发布 / 搜索                        │
│  ├─ WeatherQueryTool   — 天气查询（Demo）                              │
│  └─ StatsQueryTool     — 统计查询（测试用）                             │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ Guard 守卫
                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  Guard 层（工具调用守卫）                                 │
│  GuardedToolCallback (ToolCallback 代理薄壳)                              │
│  ├─ ToolGuardGate — 决策小步：投票 + guard span 观测 + 分流               │
│  │    └─ ToolGuardManager.evaluate() → List<ToolGuardPolicy>              │
│  │         ├─ HighRiskListPolicy     — 高危工具精确匹配                  │
│  │         ├─ ConfirmToolPolicy      — 需确认工具列表                    │
│  │         ├─ PatternMatchPolicy     — 正则匹配拦截                      │
│  │         ├─ RateLimitPolicy        — Redis 频率限制                    │
│  │         └─ ... 纯无状态策略，零业务 Service 依赖                      │
│  └─ ToolCallExecutor — 执行小步：占位符解析 + 调用 + 限长                 │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ AOP
                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  Permission 层（数据权限校验）                             │
│  @RequiredDataPermission → ToolPermissionAspect (AOP 切面)              │
│  └─ PermissionValidatorFactory → DataPermissionValidator                 │
│       ├─ BlogPermissionValidator   — 博客归属权校验                     │
│       └─ UserPermissionValidator   — 用户身份校验                       │
└──────────────────────────┬──────────────────────────────────────────────┘
                           │ Spring AI SDK
                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     AI SDK 层（基础设施）                                 │
│  DashScopeApi + ChatClient                                              │
│  ├─ 同步: prompt().user(x).call().content()                            │
│  └─ 工具: 仅由 TaskExecutor 创建独立 ChatClient 调用                    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 层叠结构详解

### 3.1 注解层 —— `@TargetTool`

**文件**: `annotation/TargetTool.java`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component  // 内含 @Component 语义，标注后自动成为 Spring Bean
public @interface TargetTool {
    boolean active() default true;  // 是否激活该工具
}
```

**设计要点**:

| 要点 | 说明 |
|------|------|
| **语义复合** | `@TargetTool` 本身已带 `@Component`，标注一个类 = 声明为 Spring Bean + 标记为 AI 工具 |
| **开关控制** | `active = false` 可临时停用某工具，`ToolBeanCollector` 启动时自动跳过 |
| **与 Spring AI 的关系** | 只负责标记 Bean 粒度，方法级别的 `@Tool` 注解仍使用 Spring AI 官方注解 |

---

### 3.2 配置层 —— AgentConfig

**文件**: `config/AgentConfig.java`

```java
@Configuration
@Slf4j
public class AgentConfig {

    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("ai-worker-");
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        return executor;
    }

    @Bean("subtaskExecutor")
    public Executor subtaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("subtask-");
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        return executor;
    }

    @Bean
    public ChatMemory chatMemory() {
        JdbcChatMemoryRepository repository = JdbcChatMemoryRepository.builder()
            .jdbcTemplate(jdbcTemplate).build();
        return MessageWindowChatMemory.builder()
            .maxMessages(10)
            .chatMemoryRepository(repository)
            .build();
    }

    @Bean("aliibabaChatClient")
    public ChatClient chatClient(DashScopeChatModel chatModel, ChatMemory chatMemory,
                                 ToolBeanCollector toolBeanCollector) {
        // 系统提示词已外置：由调用方每次请求经 PromptService 注入（见 3.13）
        return ChatClient.builder(chatModel)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }
}
```

> **关键变更 v1→v2**: 不再通过 `.defaultToolCallbacks()` 将工具注册到 ChatClient。Phase 1 的 AI 调用是纯文本的。工具由 TaskPlanner 在 Phase 2 中通过 TaskExecutor 调用。
>
> **关键变更 v2.1（提示词外置）**: 删除 `.defaultSystem(...)`。系统提示词不再硬编码在 AgentConfig，改由 5 个 LLM 调用点每次请求 `promptService.render("agent.system.main", ...)` 动态注入（支持按用户个性化；Langfuse 改模板后按缓存 TTL 生效，无需重启）。主 / 子 Agent 两个 ChatClient 的系统提示词均已外置。

### 3.3 DashScopeHttpConfig

**文件**: `config/DashScopeHttpConfig.java`

为 AI API 调用提供独立于业务接口的连接池：

| 模式 | HTTP 客户端 | 连接池参数 | 超时设置 |
|------|-------------|-----------|---------|
| **同步** (JSON) | Apache HttpClient 5 | 最大 200 连接，空闲 30s 回收 | 连接 10s，读取 60s |
| **流式** (SSE) | Reactor Netty | 最大 200 连接，空闲 30s 回收 | 连接 10s，响应 30min |

### 3.4 控制层 —— ChatController

**文件**: `controller/ChatController.java`

**路由表**:

| 路径 | 方法 | 说明 |
|------|------|------|
| `POST /agent/string/send` | `chat()` | 主入口，根据 Accept 头切换模式 |

**内容协商**:

```
Accept: text/event-stream    → SSE 流式 + 工具调用
Accept: */* 或 无 Accept 头  → 普通 JSON 同步响应
```

### 3.5 服务层 —— AiService / AiServiceImpl

**文件**: `service/AiService.java`, `service/impl/AiServiceImpl.java`（编排层，2026-08 拆分后仅 ~200 行）

#### 接口定义

```java
public interface AiService {
    /** JSON 同步模式：等待完整回复后返回 */
    String chatReturnStringResult(String content, String conversationId);

    /** SSE 流式模式（双模端点，Accept: text/event-stream） */
    void chatWithToolcall(String content, String conversationId, SseEmitter emitter, AgentSpan rootSpan);
}
```

#### 核心实现 — 编排（拆分后）

`AiServiceImpl` 已收敛为纯协调层，三段职责分别下沉到独立组件（拆分提交 `3af10f8` / `24986d1` / `89a1ce7`）：

```
JSON 模式：PromptHookExecutor.execute() → ChatClient 同步调用 → HistoryRecorder.recordBestEffort()

SSE 模式：PromptHookExecutor.execute() →（异步线程 resume 根 span）→
          StreamingChatInvoker.streamWithRetry() → SseResponseProcessor.process()
```

| 组件 | 职责 |
|------|------|
| `hook/PromptHookExecutor` | Hook 链执行 + 决策（BLOCK/REPLACE/PASS），双模共用 |
| `stream/StreamingChatInvoker` | ChatModel 层流式直调 + SSE 逐 token 推送 + 3 次重试（错误回喂 LLM）+ phase1 观测 |
| `stream/SseResponseProcessor` | AfterAiHook 链 + decision 观测 + AiResponseRouter 路由 + 历史落库 |
| `history/HistoryRecorder` | 最佳努力历史落库（失败静默），JSON/SSE 双模共用 |

> Phase 2（PLANNING）由 AiResponseRouter 在决策时委托给 TaskPlanner（见 3.7）。

### 3.6 AfterAiHook 后处理层

#### AfterAiHook 接口

```java
@FunctionalInterface
public interface AfterAiHook {
    HookResult afterAi(String originalInput, String aiResponse, ChatContext context);
}
```

只做轻量判断，不执行任务。

#### AfterAiHookChain 优先级短路

**BLOCK > REPLACE > PLANNING > PASS**

PLANNING 具有"传染性"——只要一个 Hook 认为需要拆解，整个请求就进入 TaskPlanner。

#### TaskTriggerHook

```java
private static final List<String> TRIGGERS = List.of(
    "对比", "总结", "分析", "统计", "归纳", "报告",
    "比较", "差异", "变化", "趋势", "分别"
);
```

额外跳过条件：
- AI 回复 < 20 字
- AI 回复含"无法"、"不能"、"抱歉"

#### AiResponseRouter

```java
public void route(HookResult result, String input, String aiResponse,
                  ChatContext ctx, SseEmitter emitter) {
    switch (result.getDecision()) {
        case BLOCK   -> errorEvent + complete;
        case REPLACE -> escapeJson(replacedText) + complete;
        case PLANNING -> taskPlanner.planAndExecuteAsync(...);
        default      -> escapeJson(aiResponse) + complete;
    }
}
```

### 3.7 TaskPlanner 规划执行层

#### 主循环

```java
for (int round = 0; round < MAX_ROUNDS; round++) {
    log.info("========== [Round {}] ① 规划拆解 ==========", r);
    List<SubTask> tasks = decompose(input, currentResponse, toolCallbacks, history);
    if (tasks.isEmpty()) {
        log.warn("========== [Round {}] ② 无需执行, 保持原回复 ==========", r);
        return currentResponse;  // 空计划兜底
    }

    // SSE 推送规划状态
    // 检查 CONFIRM 工具 → 存快照，暂停

    log.info("========== [Round {}] ② 执行子任务 ==========", r);
    // 执行
    TaskQueue queue = new TaskQueue(tasks);
    TaskExecutor executor = new TaskExecutor(toolCallbacks, userId, chatClient, timeoutMs);
    executor.executeAll(queue);

    log.info("========== [Round {}] ③ 聚合结论 ==========", r);
    // 聚合
    currentResponse = merge(currentResponse, queue);
    // 推送结论生成完成
    safeSend(emitter, progressEvent("merging", "结论生成完成"));
}
```

#### decompose() — AI 规划 + Java 校验

**askAiForPlan()**: 调 AI 返回 JSON 数组。已完成/失败的工具只传 50 字摘要，不传完整 result。

**validatePlan() 三层校验**:

```
① JSON 语法 → parseable? isArray?
② 工具存在性 → callbackIndex.containsKey()
③ 历史状态 → isCompleted / isFinalFailed
校验通过 → 构建 TOOL_CALL SubTask
强制追加 LLM_REASON（防止 AI 只返回原始数据）
```

**空计划兜底**: 所有工具被跳过（不存在/已完成/已失败），返回空列表，主循环退出。

#### SubTask 类型

| 类型 | 用途 | 执行方式 |
|------|------|---------|
| `TOOL_CALL` | 调用 `@Tool` 方法 | 匹配 ToolCallback，call(jsonArgs, toolContext) |
| `LLM_REASON` | 基于工具结果做聚合推理 | 调 ChatClient 生成结论 |

#### TaskExecutor

串行执行：`while (!queue.isAllDone())` 循环取 READY 任务。

- TOOL_CALL 失败 → `markFailed()`，LLM_REASON 自动注入失败摘要
- LLM_REASON 基于已完成结果 + 失败摘要生成结论

### 3.8 SSE 事件协议

所有 SSE 事件通过 `SseUtils` 构建（ObjectMapper 序列化，杜绝手动拼接）。

| 类型 | 方法 | JSON |
|------|------|------|
| 错误 | `errorEvent(msg)` | `{"error":"xxx","code":5001}` |
| 进度 | `progressEvent(stage, text)` | `{"type":"progress","stage":"planning","text":"..."}` |
| 步骤 | `stepEvent(toolName, status)` | `{"type":"progress","stage":"step","toolName":"q","status":"RUNNING"}` |
| 确认 | `confirmEvent(text)` | `{"type":"progress","stage":"confirm","text":"需要确认"}` |
| 元数据 | `metaEvent(conversationId)` | `{"type":"meta","conversationId":"..."}` |

### 3.9 工具层 —— ToolBeanCollector & Tool 实现

#### ToolBeanCollector（自动收集器）

启动时扫描 `@TargetTool` Bean → `ToolCallbacks.from()` 转为 `ToolCallback[]` → 每个用 `GuardedToolCallback` 包装。

> **v2.1（提示词外置）**: `GuardedToolCallback` 额外注入 `ToolDefinitionProvider`，其 `getToolDefinition()` 不再直接委托 delegate，而是经 `ExternalizedToolDefinitionProvider` 用 `ToolDefinition.builder()` 重建——工具描述（description + inputSchema 参数描述）优先取外置模板（Langfuse → 内置 `resources/prompts/agent.tool.*.txt`），取不到回退 `@Tool`/`@ToolParam` 注解。覆盖经 `getToolDefinition()` 自动传播到 LLM 函数 schema 与 TaskPlanner 的工具列表（见 3.13）。

#### BlogTool

| 工具方法 | 描述 | 参数 |
|---------|------|------|
| `queryPublishedBlogs` | 当前用户已发布博客，按点赞降序 top5 + 总数（紧凑投影：标题+内容摘要80字+点赞数，防上下文膨胀） | `ToolContext` |
| `publishTestBlog` | 发布一篇测试博客 | `ToolContext` |
| `queryBlogsByTitle` | 按标题模糊搜索博客，top10 + 总数（紧凑投影） | `title: String` |

#### BlogQueryTool（博客详情/评论/他人博客）

| 工具方法 | 描述 | 参数 |
|---------|------|------|
| `queryBlogById` | 单篇博客详情（标题/内容摘要/点赞数/评论数/作者昵称） | `blogId: Long` |
| `queryBlogComments` | 某篇博客的评论列表（按时间升序） | `blogId: Long` |
| `queryUserBlogs` | 某用户发布的博客列表（可查他人，不限于自己，top10） | `userId: Long` |

#### ShopQueryTool（店铺查询）

| 工具方法 | 描述 | 参数 |
|---------|------|------|
| `queryShopTypes` | 平台所有店铺类型（美食/酒店/影院等分类） | 无 |
| `queryShopsByType` | 按店铺类型查店（评分降序，top10） | `typeId: Long`, `current: Integer`(可选) |
| `queryShopById` | 单个店铺详情（名称/商圈/地址/人均/评分/销量/营业时间） | `shopId: Long` |

#### VoucherQueryTool（优惠券/订单）

| 工具方法 | 描述 | 参数 |
|---------|------|------|
| `queryVouchersByShop` | 某店铺可用优惠券（含秒杀券库存） | `shopId: Long` |
| `queryMyVoucherOrders` | 当前用户自己的优惠券订单（按下单时间倒序 top10） | 无 |

#### UserQueryTool（用户/关注）

| 工具方法 | 描述 | 参数 |
|---------|------|------|
| `queryUserProfile` | 用户公开资料（昵称/城市/简介/粉丝数/关注数/积分，不含手机号等隐私字段） | `userId: Long` |
| `queryMyFollows` | 当前用户关注的用户列表 | 无 |

#### StatsQueryTool（测试）

| 工具方法 | 描述 |
|---------|------|
| `queryTotalShops` | 查询店铺总数 |
| `queryTotalUsers` | 查询注册用户数 |
| `queryTotalBlogs` | 查询博客总数 |

### 3.10 守卫层 —— PromptGuard

在 ToolCallback 代理层实现第一道防线，前置拦截高风险调用。

| 策略 | 判断依据 |
|------|---------|
| `HighRiskListPolicy` | YAML `block-tools` 精确匹配 `ToolDefinition.name()` |
| `ConfirmToolPolicy` | YAML `confirm-tools` 精确匹配 |
| `PatternMatchPolicy` | 正则匹配 `toolName` 和 `arguments` |
| `RateLimitPolicy` | Redis 计数器，每会话速率限制 |

**决策聚合**: 任一 BLOCK → 一票否决 → 最终 BLOCK。

### 3.11 权限层 —— 可插拔数据权限校验

AOP 切面 `ToolPermissionAspect` + 策略模式 `PermissionValidatorFactory`。

新增资源只需两步：
1. 创建 `XxxPermissionValidator implements DataPermissionValidator`
2. 标注 `@Component`

> 详见 [3.6 权限层文档]（结构不变，此处略去重复细节）

### 3.12 PromptHook 前置拦截层

在 AI 调用前执行的链式 Hook。当前实现：`InjectionDetectHook`（注入检测）、`SensitiveWordHook`（敏感词脱敏）。

**执行规则**:

```
PASS → currentInput 不变，继续
REPLACE → 替换 currentInput
BLOCK → 立即短路
异常 → Fail-Open 降级 PASS
```

### 3.13 提示词外置层 —— `com.hmdp.agent.prompt`

**引入**: v2.1（2026-08-06）。解决系统提示词 / 工具描述硬编码在 Java 代码里、改提示词要重编译重部署、无法多版本 / 运行期热改 / 按用户注入上下文的问题。事实源 = **Langfuse Prompt Management（云）+ 内置模板兜底**，不使用 DB 表。

#### 包结构

```
com.hmdp.agent.prompt/
├── PromptKeys.java              # 键常量（Langfuse Prompt 名 = 内置资源文件名，一一对应）
├── PromptService.java           # 门面：render(文本) / renderTool(工具描述)
├── PromptRenderer.java          # 静态 {{var}} 替换，缺失变量保留字面量 + WARN，永不抛
├── config/PromptProperties.java # @ConfigurationProperties("agent.prompt")
├── repo/
│   ├── BuiltinPromptRepository.java    # classpath:prompts/{key}.txt 兜底（首读缓存）
│   └── LangfusePromptRepository.java   # RestClient + 双 Caffeine 缓存
├── impl/DefaultPromptService.java      # 编排 remote → builtin → Fail-Open，埋 agent.prompt.{key}
├── model/ResolvedToolPrompt.java       # 工具描述解析结果（description + params）
└── seed/                               # PromptSeeder + PromptAdminController（seed/reload 端点）
```

内置模板：`src/main/resources/prompts/*.txt`（6 文本 + 7 工具描述），内容与 `PromptKeys` 完全对应。

#### 运行时优先链（三级 Fail-Open）

```
Langfuse 云 → 内置资源文件 → @Tool 注解 / 空兜底
  (getPrompt 200)   (404/网络失败)      (工具描述专用)
```

- **文本模板**（系统提示词 / 规划 / 执行 / 聚合）：Langfuse 取到 → 用；取不到 → 内置 `.txt`；内置也没有 → 空串（渲染器保证不抛）。
- **工具描述**：Langfuse → 内置 → `@Tool`/`@ToolParam` 注解（delegate 原始定义）。

#### LangfusePromptRepository 双缓存（2026-08-06 实测 API）

- 端点：`GET {base}/api/public/prompts?name={key}&label=production`（**name 是查询参数**，非路径段）；200 响应内容在**顶层 `prompt` 字段**。
- `contentCache`：成功文本 + **404 负结果**（TTL 5m）——404 是确定性结果，负缓存防每请求刷 404。
- `failureCache`：网络失败 / 5xx 瞬时熔断（TTL 30s）——防 Langfuse 宕机风暴，30s 后自动恢复探测。
- RestClient connect/read 超时 2s；未配置（base-url/basic-auth 空）不发任何远程请求（本地 IDE 直接走内置）。

#### 5 个 LLM 调用点（系统提示词每次请求动态注入）

| 调用点 | 模板键 | 说明 |
|--------|--------|------|
| `AiServiceImpl` JSON 路径 | `agent.system.main` | `.system(promptService.render(...))` |
| `AiServiceImpl` SSE 路径 | `agent.system.main` | Prompt 显式加 `SystemMessage`（此前 SSE 无系统提示词） |
| `TaskPlanner` 规划路径 | `agent.system.planner` + `agent.prompt.planner` | 规划模板外置，`{{planStart/End}}` 注入 wire 标记 |
| `SubTaskAgent` 执行路径 | `agent.system.subagent` + `agent.prompt.subagent.execution` | 模板用 `SubAgentPromptBuilder.buildVariables(plan)` 组装变量 |
| `TaskExecutor` 回退聚合 | `agent.system.main` + `agent.prompt.task.merge` | 回退路径（`feature.subagent.enabled=false`） |

#### 工具描述外置

`ExternalizedToolDefinitionProvider.resolve(delegate)`：`renderTool("agent.tool.{name}")` 取到 JSON（`{"description":"...","params":{"参数":"描述"}}`）→ 用 `ToolDefinition.builder()` 重建，覆盖 description + `inputSchema` 里的参数描述；JSON 解析失败 → 保留原 schema（Fail-Open）。provider 级 Caffeine 缓存（TTL = `agent.prompt.cache-ttl`）避免每请求 parse。覆盖经 `GuardedToolCallback.getToolDefinition()` 传播到 LLM 函数 schema 与 TaskPlanner 工具列表。

#### 配置与观测

```yaml
agent:
  prompt:
    enabled: true
    base-url: ${LANGFUSE_BASE_URL:}     # 空则不启用远程拉取
    basic-auth: ${LANGFUSE_BASIC_AUTH:} # 裸 base64，不含 "Basic " 前缀
    default-label: production
    cache-ttl: 5m
    failure-cache-ttl: 30s
    timeout: 2s
    tool-description-enabled: true
    seed-enabled: false                 # 种子/清缓存端点开关
```

观测：每次渲染埋 `agent.prompt.{key}` span（`AgentSpanSpec.PROMPT`），属性 `prompt.source=remote|builtin|missing` + `rendered_len`。种子端点 `POST /agent/prompt/seed|reload`（seed 推内置模板到 Langfuse 打 production label；reload 清双缓存），`seed-enabled=false` 时返回 403。

---

## 4. 核心数据流

### 4.1 JSON 同步模式

```mermaid
sequenceDiagram
    participant Client as 前端
    participant CC as ChatController
    participant AS as AiServiceImpl
    participant PromptHook as PromptHookChain
    participant CC2 as ChatClient
    participant LLM as DashScope LLM

    Client->>CC: POST /agent/string/send<br/>content="你好"
    CC->>AS: chatReturnStringResult("你好", conversationId)
    AS->>PromptHook: execute(content, ctx)
    PromptHook-->>AS: PASS / REPLACE / BLOCK
    alt BLOCK
        AS-->>CC: "❌ " + reason
    else PASS/REPLACE
        AS->>CC2: chatClient.prompt().user(finalContent).call()
        CC2->>LLM: 用户消息
        LLM-->>CC2: "你好！我是智能助手"
        CC2-->>AS: content
        AS-->>CC: Result.ok(content + conversationId)
        CC-->>Client: {content: "你好！...", conversationId: "..."}
    end
```

### 4.2 SSE 两阶段模式（含 Phase 1 + Phase 2）

```mermaid
sequenceDiagram
    participant Client as 前端
    participant CC as ChatController
    participant AS as AiServiceImpl
    participant PromptHook as PromptHookChain
    participant CC2 as ChatClient
    participant LLM as DashScope LLM
    participant AfterHook as AfterAiHookChain
    participant Router as AiResponseRouter
    participant Planner as TaskPlanner
    participant Executor as TaskExecutor
    participant Tool as @Tool 方法

    Client->>CC: POST /agent/string/send<br/>content="统计一下店铺数量"<br/>Accept: text/event-stream
    CC->>CC: new SseEmitter(30min)
    CC->>AS: chatWithToolcall(content, emitter)

    Note over AS: === Phase 1：纯文本 AI ===
    AS->>PromptHook: execute(content, ctx)
    PromptHook-->>AS: PASS

    AS->>CC2: prompt().user(content).call()
    Note over CC2: 无 .tools()，纯文本
    CC2->>LLM: 用户消息
    LLM-->>CC2: "我帮你查一下店铺数据"
    CC2-->>AS: "我帮你查一下店铺数据"
    Note over AS: [Phase1] AI 初次回复

    Note over AS: === AfterAiHook 决策 ===
    AS->>AfterHook: execute(input, response, ctx)
    AfterHook->>AfterHook: TaskTriggerHook 检查触发词<br/>"统计"命中 → PLANNING
    AfterHook-->>AS: planningRequired()

    AS->>Router: route(PLANNING, ...)
    Router->>Planner: planAndExecuteAsync(input, response, emitter)

    Note over Planner: === Phase 2：TaskPlanner ===
    Note over Planner: [Round 1] ① 规划拆解
    Planner->>Planner: decompose()
    Planner->>CC2: askAiForPlan() → AI 规划
    CC2->>LLM: prompt: "需要调什么工具?"
    LLM-->>CC2: [{"tool":"queryTotalShops","params":{}}]
    Planner->>Planner: validatePlan() 三层校验<br/>→ 通过，追加 LLM_REASON

    Planner-->>Client: SSE: progress("planning", "规划完成：...")

    Note over Planner: [Round 1] ② 执行子任务
    Planner-->>Client: SSE: step("queryTotalShops", "RUNNING")

    Planner->>Executor: executeAll(queue)
    Executor->>Executor: executeTool()
    Executor->>Tool: callback.call(jsonArgs, toolContext)
    Tool-->>Executor: "24 家店铺"
    Executor->>Executor: executeLlmReason()
    Executor->>CC2: prompt("基于以上数据...")
    CC2->>LLM: 聚合
    LLM-->>CC2: "当前共有 24 家店铺"
    Executor-->>Planner: 完成

    Note over Planner: [Round 1] ③ 聚合结论
    Planner-->>Client: SSE: progress("merging", "正在生成结论...")
    Planner-->>Client: SSE: progress("merging", "结论生成完成")

    Note over Planner: [Round 2] ① 规划拆解
    Planner->>Planner: decompose() → 空计划
    Note over Planner: [Round 2] ② 无需执行

    Planner-->>Client: "当前共有 24 家店铺..."
    Planner->>CC: emitter.complete()
    CC-->>Client: SSE 流结束
```

### 4.3 防护路径（守卫 + 权限拦截）

```mermaid
sequenceDiagram
    participant Tool as @Tool 方法
    participant GC as GuardedToolCallback
    participant Guard as ToolGuardManager
    participant Aspect as ToolPermissionAspect
    participant Factory as PermissionValidatorFactory
    participant Validator as BlogPermissionValidator

    Tool->>GC: call(functionPayload, toolContext)
    GC->>Guard: evaluate()
    Guard->>Guard: HighRiskListPolicy → ABSTAIN
    Guard->>Guard: ConfirmToolPolicy → ABSTAIN
    Guard->>Guard: PatternMatchPolicy → ABSTAIN
    Guard->>Guard: RateLimitPolicy → ABSTAIN
    Guard-->>GC: ALLOW

    GC->>Aspect: joinPoint.proceed()
    Aspect->>Factory: getValidator("blog")
    Factory-->>Aspect: BlogPermissionValidator
    Aspect->>Validator: validate(userId, blogId, DELETE)
    Validator-->>Aspect: true (通过)

    Aspect-->>GC: Blog 结果
    GC-->>Tool: 正常返回
```

---

## 5. 关键设计决策

### 5.1 为什么 Phase 1 不带工具？

| 方案 | 问题 |
|------|------|
| Phase 1 就绑定工具 | AI 第一次回复直接调了工具，规划器又规划一遍，导致重复执行。需要脆弱的 `markAiCompletedTools` 做关键词去重 |
| **Phase 1 纯文本** ✅ | 工具只执行一次，无重复，日志清晰 |

### 5.2 为什么 AI 规划 + Java 校验？

| 方案 | 问题 |
|------|------|
| 关键词/n-gram 匹配（v1/v2） | "发布博客"误匹配 publishTestBlog，维护成本高 |
| **AI 规划 + Java 校验（v3）** ✅ | AI 理解自然语言决定调什么，Java 做安全兜底 |

### 5.3 为什么用 `@TargetTool` 而非 Spring AI 原生注册？

每新增工具都要改 `AgentConfig` 违背开闭原则。`@TargetTool` + 自动扫描只需加注解。

### 5.4 为什么工具方法用 `ToolContext` 而非 `UserHolder`？

Spring AI 的工具回调在 SDK 内部线程执行，`ThreadLocal` 可能已丢失。通过 `toolContext` 显式传递 userId。

### 5.5 为什么权限校验用策略模式 + AOP？

if-else 硬编码违背 OCP。策略模式 + AOP 新增资源只需加实现类。

### 5.6 为什么设计两层守卫（PromptGuard + AOP）？

| 维度 | PromptGuard | AOP |
|------|-------------|-----|
| 拦截时机 | `call()` 前，最早介入 | `@Tool` 前，业务代码前 |
| 依赖范围 | 纯无状态：YAML、Redis、正则 | 有状态：业务 Service |
| 判断依据 | 工具名、参数、频率 | 数据归属权、用户身份 |
| 性能 | 微秒级 | 依赖 DB 查询 |

防御纵深 — 即使 AOP 失效，第一层守卫仍起作用。

### 5.7 GuardedToolCallback 为什么选代理模式？

ToolCallbacks.from() 返回的 ToolCallback 是 Spring AI 内部生成的匿名类，无法继承。代理模式零侵入。

**职责拆分（3.6 建议落地，提交 08d9ae4）**：回调本体保持代理薄壳（340→~200 行），
决策小步抽 `ToolGuardGate`（策略投票 + guard span 观测 + BLOCK/CONFIRM/ALLOW 分流），
执行小步抽 `ToolCallExecutor`（self 占位符解析 + 委托调用 + 结果限长 + 参数转换
错误友好兜底）；callBypass（审批恢复路径）随执行器迁入。公共 API（构造器/
call/callBypass/静态工具方法）零变化。

### 5.8 为什么 RateLimitPolicy 使用 Redis？

重启保持、多实例共享、自动过期。本地计数器在重启时丢失、多实例无效。

### 5.9 为什么日志配置去掉了 per-package logger？

logback.xml 中定义 `<logger>` 不设 level 会覆盖 Spring Boot yaml 的 `logging.level` 设置，导致级别控制失效。改为全部由 yaml 控制：

```yaml
logging:
  level:
    com.hmdp: WARN
    com.hmdp.agent: DEBUG
    com.hmdp.agent.tool: DEBUG
    com.hmdp.promptguard: DEBUG
    com.hmdp.prompthook: DEBUG
```

### 5.10 为什么提示词用 Langfuse Prompt Management + 内置兜底，而非本地 DB 表？

路线图原方案是自建 `agent_prompt_template` 表。实际落地改为 **Langfuse 云为事实源 + 代码内置模板兜底**：

| 维度 | Langfuse Prompt Management | 自建 DB 表 |
|------|---------------------------|-----------|
| 版本管理 / label（production/staging） | ✅ 内置 | ❌ 自己写 |
| UI 编辑 / git-sync / 变更历史 | ✅ 内置 | ❌ 自己写 |
| 运行期热改 | ✅ 按缓存 TTL 生效 | ✅ 类似 |
| 免费档可用性 | ✅ Hobby 含 | — |
| 离线 / Langfuse 不可用 | 降级内置模板（Fail-Open） | 完全自洽 |

Langfuse 已覆盖版本管理 + UI + 编辑链，自建表是重复造轮子；**内置模板兜底**保证 Langfuse 故障 / 未配置时功能不降级（与观测模块 Fail-Open 哲学一致）。

### 5.11 为什么系统提示词从启动时 `.defaultSystem()` 改为每次请求动态注入？

- **可运行期热改**：`.defaultSystem()` 在 ChatClient 构建时冻结，改提示词要重启；每次请求 `promptService.render()` 配合缓存 TTL，Langfuse 改模板后最多 5 分钟生效。
- **支持按用户个性化**：模板注入 `{{userId}}` 等上下文变量，为路线图"动态注入用户姓名/城市"目标铺路。
- **SSE 路径首次获得系统提示词**：原 SSE 路径绕开 ChatClient 直连 `dashScopeChatModel.stream()`，从未带系统提示词；改为显式构造 `SystemMessage` 后补齐。

代价：5 个调用点必须显式 `.system()`（`.system()` 是替换而非追加），漏一处 = 该路径无系统提示词，靠测试强制覆盖。

---

## 6. 扩展指南

### 6.1 添加一个新工具

1. 新建类，标注 `@TargetTool`
2. 在方法上标注 `@Tool(description = "...")`
3. 权限敏感加 `@RequiredDataPermission`
4. 重启应用 → `ToolBeanCollector` 自动注册

### 6.2 工具方法设计规范

| 规则 | 说明 |
|------|------|
| `@Tool(description)` 必填 | 清晰描述帮助 AI 决策 |
| `@ToolParam(description)` 推荐 | 帮助 AI 生成正确参数 |
| 接收 `ToolContext` 获取用户 | 不要直接用 `UserHolder` |
| 返回具体类型 | Spring AI 自动序列化送模型 |

---

## 7. 配置说明

### 7.1 关键配置项

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        model: qwen-plus-2025-07-28

agent:
  prompt:
    enabled: true
    base-url: ${LANGFUSE_BASE_URL:}     # 空则不启用远程拉取
    basic-auth: ${LANGFUSE_BASIC_AUTH:} # Langfuse Basic 认证（裸 base64）
    default-label: production
    cache-ttl: 5m
    failure-cache-ttl: 30s
    timeout: 2s
    tool-description-enabled: true
    seed-enabled: false                 # 种子/清缓存端点开关

hmdp:
  prompt-guard:
    block-tools:
      - deleteBlog
    confirm-tools:
      - publishBlog
    rate-limit:
      max-per-session: 30
      window-seconds: 60
```

### 7.2 线程池

| 名称 | 核心/最大 | 队列 | 用途 |
|------|-----------|------|------|
| `aiTaskExecutor` | 2 / 4 | 100 | Phase 1 AI 异步调用 |
| `subtaskExecutor` | 10 / 50 | 200 | Phase 2 规划执行 |

---

## 8. 监控与日志

### 8.1 日志结构

```
[Phase1] AI 初次回复, result=...           ← Phase 1 完成
[AfterAiHook] xx 触发规划                   ← 决策进入 Phase 2
[Round 1] ① 规划拆解                        ← Phase 2 各阶段
  [规划] AI 建议: [...]
  [规划] 需执行 [tool=xx, params=...]
  [规划] 追加 LLM_REASON
[Round 1] ② 执行子任务
    TOOL_CALL ✅ [tool=xx]
    LLM_REASON ✅
[Round 1] ③ 聚合结论
[Round 2] ① 规划拆解
  [规划] AI 建议: []
[Round 2] ② 无需执行, 保持原回复
```

### 8.2 关键指标

| 指标 | 获取方式 |
|------|---------|
| AI 调用耗时 | `[Phase1]` 日志前后 |
| 工具调用成功率 | `TOOL_CALL ✅` vs `❌` |
| SSE 超时 | `SSE 流超时` 日志 |

---

## 附：文件清单

### Agent 模块

| 文件路径 | 角色 |
|---------|------|
| `annotation/TargetTool.java` | 工具标记注解 |
| `annotation/ToolMeta.java` | 工具业务元数据注解（keywords 触发词 + intents 意图归属，工具注册表单一体） |
| `agent/config/AgentConfig.java` | ChatClient（无默认工具）+ 线程池 |
| `agent/config/DashScopeHttpConfig.java` | DashScope HTTP 连接池 |
| `agent/controller/ChatController.java` | SSE/JSON 双模入口 |
| `agent/tool/ToolRegistry.java` | 工具注册表（工具名 + @ToolMeta 元数据聚合，单一事实源） |
| `agent/service/AiService.java` | AI 服务接口 |
| `agent/service/impl/AiServiceImpl.java` | 编排层（拆分后：Hook 段 → 流式调用 → 后处理，纯协调） |
| `agent/hook/PromptHookExecutor.java` | Hook 链执行 + 决策（双模共用） |
| `agent/stream/StreamingChatInvoker.java` | 流式调用 + 重试 + SSE 推送 |
| `agent/stream/SseResponseProcessor.java` | SSE 后处理（AfterAiHook → 路由 → 落库） |
| `agent/history/HistoryRecorder.java` | 最佳努力历史落库（双模共用） |
| `agent/response/AiResponseRouter.java` | 后处理路由器 |
| `agent/util/SseUtils.java` | SSE 事件构建 + JSON 序列化 |
| `agent/tool/ToolBeanCollector.java` | @TargetTool 自动扫描 + Guard 包装 |
| `agent/tool/impl/BlogTool.java` | 博客工具 |
| `agent/tool/impl/WeatherQueryTool.java` | 天气查询 |
| `agent/tool/impl/StatsQueryTool.java` | 统计查询（测试） |
| `agent/task/TaskPlanner.java` | 规划器（编排门面） |
| `agent/task/PlanLoopExecutor.java` | 主循环（子 Agent/回退分支分发） |
| `agent/task/SubAgentRoundExecutor.java` | 子 Agent 执行分支 |
| `agent/task/FallbackRoundExecutor.java` | 回退执行分支 |
| `agent/task/ConfirmFlowManager.java` | CONFIRM 审批流 |
| `agent/task/TaskReportHelper.java` | 历史/聚合助手 |
| `agent/task/AgentContextResolver.java` | 异步上下文解析工具 |
| `agent/task/model/SubTask.java` | 子任务数据模型 |
| `agent/task/model/SubTaskStatus.java` | 状态机枚举（五态两用：READY/RUNNING 为回退链专用） |
| `agent/task/model/TaskType.java` | 类型枚举 |
| `agent/task/model/TaskReport.java` | 执行报告 |
| `agent/task/model/TaskSnapshot.java` | 任务快照（CONFIRM 续跑） |
| `agent/legacy/task/TaskExecutor.java` | 【回退路径，已重整】串行任务执行器（`feature.subagent.enabled=false` 时由 FallbackRoundExecutor 使用，非死代码） |
| `agent/legacy/task/TaskQueue.java` | 【回退路径，已重整】回退队列（仅 FallbackRoundExecutor 使用；TaskReportHelper 已解耦） |
| `agent/legacy/plan/LegacyPlanRouter.java` | 【回退路径，已重整】legacy 规划策略（`feature.tool-routing.enabled=false` 条件装配） |
| `agent/legacy/plan/ToolRouter.java` | 【回退路径，已重整】回退规划链内部路由门面（仅被 LegacyPlanRouter 使用） |
| `agent/routing/CatalogBuilder.java` | 【活接口，已迁出】目录构建抽象——TreeCatalogBuilder/CompactCatalogBuilder 均实现它；P5 重整时从 legacy 包迁至 routing 包 |

### 权限校验模块

| 文件路径 | 角色 |
|---------|------|
| `permission/annotation/RequiredDataPermission.java` | 权限注解 |
| `permission/enums/DataAction.java` | 操作类型枚举 |
| `permission/validator/DataPermissionValidator.java` | 校验策略接口 |
| `permission/validator/PermissionValidatorFactory.java` | 校验器工厂 |
| `permission/validator/impl/BlogPermissionValidator.java` | 博客归属权校验 |
| `permission/validator/impl/UserPermissionValidator.java` | 用户身份校验 |
| `aspect/ToolPermissionAspect.java` | AOP 切面 |

### PromptGuard 守卫模块

> 包名 `guard`（旧文档 `promptguard` 为历史包名，已修正）。按职责分层（提交 0475a85）：
> guard 根 = 门面三件套，`guard/model` = 决策模型与上下文，`guard/policy` = 策略接口与实现，
> 执行小步 `ToolCallExecutor` 归工具域（tool 包）。

| 文件路径 | 角色 |
|---------|------|
| `guard/GuardedToolCallback.java` | ToolCallback 代理（薄壳：回调协议 + 元数据代理 + 上下文装配） |
| `guard/ToolGuardGate.java` | 守卫门（决策小步：策略投票 + guard span 观测 + BLOCK/CONFIRM/ALLOW 分流） |
| `guard/ToolGuardManager.java` | 策略收集与决策聚合 |
| `guard/model/GuardResult.java` | 决策结果 |
| `guard/model/Vote.java` | 投票枚举 |
| `guard/model/ToolInvocationContext.java` | 评估上下文 |
| `guard/model/ConfirmRequiredException.java` | CONFIRM 暂停信号（审批流异常，TaskPlanner/工具循环捕获） |
| `guard/policy/ToolGuardPolicy.java` | 策略接口 |
| `guard/policy/*.java` | 各策略实现（HighRiskList/ConfirmTool/PatternMatch/RateLimit） |
| `tool/ToolCallExecutor.java` | 执行小步（self 占位符解析 + 委托调用 + 结果限长 + 参数转换错误兜底） |

### PromptHook 输入拦截模块

| 文件路径 | 角色 |
|---------|------|
| `prompthook/PromptHook.java` | 前置 Hook 接口 |
| `prompthook/PromptHookChain.java` | 链式执行器 |
| `prompthook/HookResult.java` | 决策结果 |
| `prompthook/ChatContext.java` | 上下文对象 |
| `prompthook/AfterAiHook.java` | 后处理 Hook 接口 |
| `prompthook/AfterAiHookChain.java` | 后处理链式执行器 |
| `prompthook/impl/TaskTriggerHook.java` | 触发词检测 |

### 基础设施

| 文件路径 | 角色 |
|---------|------|
| `utils/UserHolder.java` | 用户上下文持有者 |
| `exception/WebExceptionAdvice.java` | 全局异常处理 |
