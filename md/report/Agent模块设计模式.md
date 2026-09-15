# Agent 模块设计模式详解

> 本文档梳理 `com.hmdp.agent` 包中使用的所有设计模式，标注每个模式的出现位置、核心代码片段和设计动机。

---

## 目录

1. [责任链模式（Chain of Responsibility）](#1-责任链模式chain-of-responsibility)
2. [工厂模式（Factory）](#2-工厂模式factory)
3. [门面模式（Facade）](#3-门面模式facade)
4. [空对象模式（Null Object）](#4-空对象模式null-object)
5. [代理/装饰器模式（Proxy / Decorator）](#5-代理装饰器模式proxy--decorator)
6. [观察者/回调模式（Observer / Callback）](#6-观察者回调模式observer--callback)
7. [构建者模式（Builder）](#7-构建者模式builder)
8. [上下文对象模式（Context Object）](#8-上下文对象模式context-object)
9. [降级链模式（Fallback Chain）](#9-降级链模式fallback-chain--多级后备策略)
10. [路由/分发器模式（Router / Dispatcher）*](#10-路由分发器模式router--dispatcher)
11. [配置驱动的工厂模式（Configurable Factory）](#11-配置驱动的工厂模式configurable-factory)

---

## 1. 责任链模式（Chain of Responsibility）

**模块中最核心的模式**，共出现三套完整实现。三套链的结构一致：接口定义 handler → 链执行器串行遍历 → 每个 handler 返回决策 → 执行器聚合/短路。

### 1.1 Prompt 前置链

在用户输入发送给 LLM **之前**执行，用于安全检测和文本脱敏。

| 组件 | 类 | 职责 |
|---|---|---|
| Handler 接口 | `PromptHook`（@FunctionalInterface） | 定义 `beforePrompt(originalInput, currentInput, context)` |
| 链执行器 | `PromptHookChain` | 收集所有 `PromptHook` Bean，串行执行 |
| Handler 实现 | `InjectionDetectHook` | 检测 Prompt 注入攻击特征，命中则 BLOCK |
| Handler 实现 | `SensitiveWordHook` | 敏感词检测并脱敏，命中则 REPLACE |
| 决策结果 | `HookResult` | 枚举 Decision：PASS / BLOCK / REPLACE / PLANNING |

**链执行规则**（`PromptHookChain.execute()`）：

```
for each hook:
    result = hook.beforePrompt(originalInput, currentInput, context)
    switch result:
        BLOCK  → 立即返回（短路，后续 Hook 不再执行）
        REPLACE → 替换 currentInput，后续 Hook 在替换后的文本上继续
        PASS   → 跳过，currentInput 不变
异常 → Fail-Open：catch 后降级为 PASS，不影响业务
```

**关键设计**：
- `originalInput`（不可变）和 `currentInput`（可被替换）分离，安全检测类 Hook 始终基于原始输入判断，不受前置脱敏干扰
- 异常 Fail-Open：Hook 自身 bug 不阻断用户正常对话

**代码位置**：
- `hook/PromptHook.java`
- `hook/PromptHookChain.java`
- `hook/impl/InjectionDetectHook.java`
- `hook/impl/SensitiveWordHook.java`

### 1.2 AI 回复后置链

在 LLM 返回结果**之后**执行，做轻量级判断（是否需要任务拆解规划）。

| 组件 | 类 | 职责 |
|---|---|---|
| Handler 接口 | `AfterAiHook`（@FunctionalInterface） | 定义 `afterAi(originalInput, aiResponse, context)` |
| 链执行器 | `AfterAiHookChain` | 收集所有 `AfterAiHook` Bean，串行执行 |
| Handler 实现 | `TaskTriggerHook` | 检测触发词（对比/总结/分析...），命中则返回 PLANNING |

**链执行规则**（`AfterAiHookChain.execute()`）：

```
anyPlanning = false
for each hook:
    result = hook.afterAi(input, response, context)
    switch result:
        BLOCK    → 立即返回（短路）
        REPLACE  → 立即返回（覆盖后续所有判断）
        PLANNING → anyPlanning = true（"传染性"聚合）
        PASS     → 忽略
if anyPlanning → 返回 planningRequired()
else → 返回 pass()
```

**与 1.1 的区别**：
- PLANNING 决策具有"传染性"：只要有一个 Hook 认为需要规划，最终就进入 TaskPlanner
- REPLACE 优先级高于 PLANNING：如果某个 Hook 替换了回复，不再进入规划

**代码位置**：
- `hook/AfterAiHook.java`
- `hook/AfterAiHookChain.java`
- `hook/impl/TaskTriggerHook.java`

### 1.3 工具守卫链

在工具调用**之前**执行，多个策略对同一次调用进行风险评估投票。

| 组件 | 类 | 职责 |
|---|---|---|
| Handler 接口 | `ToolGuardPolicy`（@FunctionalInterface） | 定义 `vote(context)` → Vote |
| 链执行器 | `ToolGuardManager` | 收集所有 `ToolGuardPolicy` Bean，遍历投票并聚合 |
| Handler 实现 | `HighRiskListPolicy` | 高危工具名单匹配（YAML 配置），命中则 BLOCK |
| Handler 实现 | `ConfirmToolPolicy` | 需确认工具名单匹配（YAML 配置），命中则 CONFIRM |
| Handler 实现 | `PatternMatchPolicy` | 正则匹配工具名/参数 JSON，命中则 BLOCK 或 CONFIRM |
| Handler 实现 | `RateLimitPolicy` | Redis 频率限制，超阈值则 BLOCK |
| 投票枚举 | `Vote` | ALLOW / CONFIRM / BLOCK / ABSTAIN |
| 决策结果 | `GuardResult` | ALLOW / BLOCK / CONFIRM（含 reason + policyName） |

**链执行规则**（`ToolGuardManager.evaluate()`）：

```
for each policy:
    vote = policy.vote(context)
    switch vote:
        BLOCK   → 记录 blockReason，立即返回（短路）
        CONFIRM → 记录 confirmReason（首个生效）
        ALLOW   → 日志记录
        ABSTAIN → 忽略
异常 → Fail-Open：catch 后跳过，不影响评估
遍历完毕：
    有 BLOCK  → return block
    有 CONFIRM → return confirm
    否则 → return allow
```

**投票优先级**：BLOCK > CONFIRM > ALLOW / ABSTAIN（与 Hook 链一致的短路语义）

**代码位置**：
- `guard/policy/ToolGuardPolicy.java`
- `guard/ToolGuardManager.java`
- `guard/policy/HighRiskListPolicy.java`
- `guard/policy/ConfirmToolPolicy.java`
- `guard/policy/PatternMatchPolicy.java`
- `guard/policy/RateLimitPolicy.java`

### 1.4 三套链的对比

| 维度 | Prompt 前置链 | AI 回复后置链 | 工具守卫链 |
|---|---|---|---|
| 执行时机 | LLM 调用前 | LLM 回复后 | 工具调用前 |
| Handler 接口 | `PromptHook` | `AfterAiHook` | `ToolGuardPolicy` |
| 链执行器 | `PromptHookChain` | `AfterAiHookChain` | `ToolGuardManager` |
| 决策枚举 | PASS / BLOCK / REPLACE | PASS / BLOCK / REPLACE / PLANNING | ALLOW / BLOCK / CONFIRM / ABSTAIN |
| 短路条件 | BLOCK | BLOCK / REPLACE | BLOCK |
| 异常处理 | Fail-Open（降级 PASS） | Fail-Open（降级 PASS） | Fail-Open（跳过） |
| 聚合特点 | 最后一个 REPLACE 生效 | PLANNING 传染性聚合 | BLOCK > CONFIRM 优先级 |

---

## 2. 工厂模式（Factory）

### 2.1 权限校验器工厂

`PermissionValidatorFactory` 在 Spring 启动时自动收集所有 `DataPermissionValidator` Bean，以 `getResourceType()` 为键注册到内部 Map。AOP 切面通过 `getValidator(resource)` 获取对应校验器。

```java
@PostConstruct
public void init() {
    for (DataPermissionValidator validator : validators) {
        validatorMap.put(validator.getResourceType(), validator);
    }
}
```

**扩展点**：新增资源类型只需添加 `DataPermissionValidator` 实现类（`@Component`），工厂和切面零修改。

**代码位置**：`permission/validator/PermissionValidatorFactory.java`

### 2.2 SSE 会话工厂

`SseSessionFactory` 统一创建会话根 span + ObservedSseEmitter + conversationId 元事件推送。此前 `chat()` 和 `confirm()` 两处 SSE 分支各手写一份相同逻辑，收敛后行为一致。

```java
public ChatSseSession open(String conversationId, Long userId) {
    AgentSpan root = agentTracer.startSession(conversationId, String.valueOf(userId));
    SseEmitter emitter = new ObservedSseEmitter(SSE_TIMEOUT, root, taskScheduler, SSE_GUARD_TIMEOUT);
    return new ChatSseSession(root, emitter);
}
```

**代码位置**：`stream/SseSessionFactory.java`

### 2.3 静态工厂方法

`HookResult` 和 `GuardResult` 通过静态工厂方法创建不可变结果对象，语义清晰：

```java
// HookResult
HookResult.pass()
HookResult.block("reason", "hookName")
HookResult.replace("replacedText", "hookName")
HookResult.planningRequired()

// GuardResult
GuardResult.allow()
GuardResult.block("reason", "policyName")
GuardResult.confirm("reason", "policyName")
```

**代码位置**：`hook/HookResult.java`、`guard/model/GuardResult.java`

---

## 3. 门面模式（Facade）

**`AgentTracer`** 是整个可观测性子系统的唯一入口（门面）。

主链路（ChatController / AiServiceImpl / TaskPlanner / SubTaskAgent）只 import `AgentTracer` + `AgentSpanSpec`，不触碰 core / support 内部。

内部协调的组件：
- `SpanLifecycle`：Micrometer Observation 创建与 Scope 管理
- `AttributeSanitizer`：属性值脱敏
- `SpanNameEncoder`：span 名编码（参数紧凑化 + 脱敏 + 限长）
- `SpanNamingStrategy`：根据后端能力决定是否编码语义后缀
- `TraceBackend`：观测后端适配（Langfuse / 未来其他）

**Fail-Open 保证**：埋点自身异常吞掉并告警日志，返回 `NoopAgentSpan`，不影响主链路。

```java
public AgentSpan start(AgentSpanSpec spec, String semantic) {
    if (!traceEnabled) return NoopAgentSpan.INSTANCE;
    try {
        return createSpan(strategy.name(spec, semantic));
    } catch (Exception e) {
        log.warn("[observability] start 失败，降级 Noop", e);
        return NoopAgentSpan.INSTANCE;
    }
}
```

**代码位置**：`observability/api/AgentTracer.java`

---

## 4. 空对象模式（Null Object）

**`NoopAgentSpan`**：当 `traceEnabled=false` 或埋点异常时返回，业务代码无需判空。

```java
public final class NoopAgentSpan implements AgentSpan {
    public static final NoopAgentSpan INSTANCE = new NoopAgentSpan();
    private NoopAgentSpan() {}

    @Override public AgentSpan set(AgentField field, String value) { return this; }
    @Override public Observation.Scope openScope() { return Observation.Scope.NOOP; }
    @Override public void end() { /* no-op */ }
}
```

**特征**：
- 单例共享（`INSTANCE`）
- 所有方法无操作，返回 `this` 支持链式调用
- `end()` 幂等空实现

**代码位置**：`observability/api/NoopAgentSpan.java`

---

## 5. 代理/装饰器模式（Proxy / Decorator）

**`GuardedToolCallback`** 是对原始 `ToolCallback` 的守卫包装。

职责：在 `call()` 前插入守卫逻辑（策略投票 + 观测 + 分流），但自身只保留回调协议（ToolCallback 接口 + 元数据代理 + 上下文装配）。决策和执行分别委托给两个内部组件：

```
GuardedToolCallback
  ├── ToolGuardGate   — 决策小步：策略投票 + guard span 观测 + BLOCK/CONFIRM/ALLOW 分流
  └── ToolCallExecutor — 执行小步：占位符解析 + 调用 + 限长 + 参数转换错误兜底
```

```java
@Override
public String call(String functionPayload, ToolContext toolContext) {
    ToolInvocationContext context = ToolInvocationContext.builder()
            .toolName(toolName)
            .arguments(functionPayload)
            // ...
            .build();
    return guardGate.run(context, toolName, functionPayload,
            () -> toolCallExecutor.execute(functionPayload, ctxForExec, uidForExec));
}
```

**特殊路径**：`callBypass()` 绕过守卫直调底层工具（审批恢复场景，工具已被用户确认，不再二次投票）。

**代码位置**：`guard/GuardedToolCallback.java`、`guard/ToolGuardGate.java`、`tool/ToolCallExecutor.java`

---

## 6. 观察者/回调模式（Observer / Callback）

**`SubAgentProgressCallback`** 接口解耦 SubTaskAgent 与 SSE 实现。

```java
public interface SubAgentProgressCallback {
    default void onExecuteStart(int taskCount) {}
    default void onToolCall(String toolName, String status) {}
    default void onMergeStart() {}
    default void onError(String message) {}
}
```

**特征**：
- 所有方法 `default` 空实现，实现方按需覆盖
- SubTaskAgent 在关键节点（开始执行、工具调用、合并完成、异常）回调
- SSE 实现（`SseSubAgentCallback`）通过此接口推送进度事件给前端

**代码位置**：`subagent/callback/SubAgentProgressCallback.java`

---

## 7. 构建者模式（Builder）

**`AgentContext.Builder`** 构建不可变的请求级上下文。

```java
AgentContext ctx = AgentContext.builder()
        .userId(userId)
        .conversationId(conversationId)
        .originalInput(content)
        .history(chatMemory.get(conversationId))
        .rootSpan(rootSpan)
        .build();
```

**特征**：
- `AgentContext` 是 `final` 类 + `private` 构造器，只能通过 Builder 创建
- 所有字段不可变（`final`），只有 `attributes`（ConcurrentHashMap）支持运行期扩展写入
- `withOriginalInput()` 派生新上下文（快照恢复用），其余字段（含 attributes 引用）不变

**代码位置**：`context/AgentContext.java`

---

## 8. 上下文对象模式（Context Object）

**`AgentContext`** 是请求级上下文的单一载体，统一承载散落在多处的状态：

| 字段 | 说明 |
|---|---|
| `userId` | 当前登录用户 ID |
| `conversationId` | 会话 ID（多轮对话标识） |
| `originalInput` | 用户原始输入（Hook 替换前的原文） |
| `history` | 会话历史（Hook 链用） |
| `rootSpan` | 观测根 span（跨线程挂载用） |
| `attributes` | 扩展点 Map（阶段标记等临时信息） |

**传播机制**：

```
请求入口 → AgentContext.builder().build() → AgentContextHolder.set(ctx)
    │
    ├── 同步段：AgentContextHolder.get() 直接读取
    │
    └── 异步边界：AgentContextPropagator（TaskDecorator）
         ├── 提交线程：捕获 AgentContext
         ├── 执行线程：恢复 AgentContext
         └── finally：清理（防止线程复用污染）
```

**代码位置**：
- `context/AgentContext.java` — 上下文对象
- `context/AgentContextHolder.java` — ThreadLocal 载体
- `context/AgentContextPropagator.java` — 异步边界传播器（TaskDecorator）

---

## 9. 降级链模式（Fallback Chain / 多级后备策略）

> 本质是责任链的降级应用：每个节点是一个数据源，前一个失败时自动 fallback 到下一个，直到命中或全部耗尽。

**`DefaultPromptService`** 实现了三层提示词降级链：

```
render(promptKey, vars):
    if 总开关关闭 → 直接内置
    远程仓库(Langfuse) 获取
        ├── 命中 → 使用远程模板（链终止）
        └── 未命中/异常 → 本地数据库获取
                            ├── 命中 → 使用本地模板（链终止）
                            └── 未命中/异常 → 内置模板(classpath)（链终止）
    PromptRenderer.render(template, vars)  ← 统一渲染，永不抛异常
```

**降级优先级**：**Langfuse → 本地数据库 → 内置模板 → Fail-Open**

**与标准责任链的区别**：
- 标准责任链：每个 handler 可能处理也可能不处理，链的终止条件是"某个 handler 接管了请求"
- 降级链：每个节点的终止条件是"成功获取数据"，失败时自动 fallback 到下一层
- 共同点：都是顺序遍历、短路返回（命中即终止）

**实现类**：
- `RemotePromptRepository`（接口）→ `LangfusePromptRepository` 实现
- `LocalPromptRepository` → MyBatis-Plus 查询本地数据库
- `BuiltinPromptRepository` → `classpath:prompts/{key}.txt` 首读后缓存

**代码位置**：
- `prompt/PromptService.java`（接口）
- `prompt/impl/DefaultPromptService.java`（实现）
- `prompt/repo/RemotePromptRepository.java`
- `prompt/repo/LocalPromptRepository.java`
- `prompt/repo/BuiltinPromptRepository.java`

---

## 10. 路由/分发器模式（Router / Dispatcher）*

> *非 GoF 23 标准模式，属于枚举分发解耦变体。核心思想：将 switch-case 分发逻辑从调用方剥离到独立组件，使调用方（AiServiceImpl）不感知具体路由规则。

**`AiResponseRouter`** 根据 `HookResult.Decision` 枚举分发到对应处理器：

```java
switch (result.getDecision()) {
    case BLOCK → {
        SseUtils.safeSend(emitter, SseUtils.errorEvent(result.getReason()));
        emitter.complete();
    }
    case REPLACE → {
        SseUtils.safeSend(emitter, SseUtils.escapeJson(result.getReplacedText()));
        emitter.complete();
    }
    case PLANNING → {
        taskPlanner.planAndExecuteAsync(input, aiResponse, ctx, emitter);
    }
    default → {  // PASS
        if (!contentStreamed) {
            SseUtils.safeSend(emitter, SseUtils.escapeJson(aiResponse));
        }
        emitter.complete();
    }
}
```

**路由规则**：

| Decision | 处理方式 |
|---|---|
| BLOCK | 推送错误消息，结束 SSE |
| REPLACE | 推送替换文本，结束 SSE |
| PLANNING | 委托 TaskPlanner 异步规划执行 |
| PASS | 推送原始回复（若未流式推送），结束 SSE |

**代码位置**：`response/AiResponseRouter.java`

---

## 11. 配置驱动的工厂模式（Configurable Factory）

> 工厂模式的变种：产物类型不由调用方硬编码指定，而是通过外部配置（YAML / 环境变量）在运行时解析并组装。

**`TraceBackendAssembler`** 按 `hmdp.ai-observability.backend.type` 配置解析并组装 `TraceBackend` 实现，解耦观测后端与业务埋点代码。

`AgentTracer` 构造时通过装配器获取后端实例：

```java
@Autowired
public AgentTracer(SpanLifecycle lifecycle, AttributeSanitizer sanitizer,
                   TraceProperties props, TraceBackendAssembler backendAssembler,
                   SpanNameEncoder encoder) {
    this.backend = backendAssembler.assemble();  // 按配置组装后端
    this.strategy = new SpanNamingStrategy(backend, encoder, ...);
}
```

`SpanNamingStrategy` 根据后端能力推导 span 名是否编码语义后缀（auto 跟后端能力）。

**代码位置**：`observability/backend/TraceBackendAssembler.java`、`observability/core/SpanNamingStrategy.java`

---

## 附：模式速查表

| # | 模式 | 核心类 | 设计动机 |
|---|---|---|---|
| 1 | 责任链 | `PromptHookChain`、`AfterAiHookChain`、`ToolGuardManager` | 安全检测/后处理/守卫评估的可插拔扩展 |
| 2 | 工厂 | `PermissionValidatorFactory`、`SseSessionFactory`、静态工厂方法 | 按类型创建实例、统一创建流程 |
| 3 | 门面 | `AgentTracer` | 统一观测入口，隔离 core/support 内部 |
| 4 | 空对象 | `NoopAgentSpan` | Fail-Open 兜底，业务代码无需判空 |
| 5 | 代理/装饰器 | `GuardedToolCallback` | 给原始 ToolCallback 插入守卫逻辑 |
| 6 | 观察者/回调 | `SubAgentProgressCallback` | 解耦 SubTaskAgent 与 SSE 推送 |
| 7 | 构建者 | `AgentContext.Builder` | 构建不可变上下文对象 |
| 8 | 上下文对象 | `AgentContext` + `AgentContextHolder` + `AgentContextPropagator` | 请求级状态的统一载体与跨线程传播 |
| 9 | 降级链 | `DefaultPromptService` | 提示词获取的多级后备降级（责任链变种） |
| 10 | 路由/分发器* | `AiResponseRouter` | 按决策枚举分发到对应处理器（非 GoF 标准） |
| 11 | 配置驱动工厂 | `TraceBackendAssembler` | 按配置运行时组装观测后端（工厂变种） |
