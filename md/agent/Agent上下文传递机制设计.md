# Agent 上下文传递机制设计（AgentContext）

> **版本**: v1.1
> **日期**: 2026-08
> **状态**: 第 1 步（骨架落地）已实施 ✅；第 2/3 步（消费方迁移、ChatContext 并入）待实施
> **目标**: 设计一个专门给 AI 链路传递上下文的统一机制，替代当前散落的手递方案
> **相关**: `md/agent/Agent模块架构设计.md`、`上下文传递优化设计.md`（token 压缩，不同主题）

---

## 1. 背景与问题

AI 链路中"当前请求是谁、哪个会话、原始输入是什么、观测根在哪"这些上下文，目前散落在 **5+ 个载体**里各自传递：

| 载体 | 用途 | 问题 |
|------|------|------|
| `UserHolder`（静态 ThreadLocal ×2） | 登录态 userId / UserDTO | 仅 Web 线程有效，异步线程丢失（agent 大量异步） |
| `ChatContext`（hook 链专用） | userId / conversationId / originalInput / history / rootSpan / pendingSnapshot | 只服务 Hook 链，后半段还要继续手递 |
| Spring AI `ToolContext` | 工具执行时注入 userId / conversationId | 依赖 executor 手动塞，来源不一 |
| `TaskSnapshot` | CONFIRM 快照恢复时携带 userId / conversationId / rootSpan | 每次恢复都要手动重建一套 |
| `ToolBeanCollector.conversationId`（单例 volatile） | 意图给 GuardedToolCallback 用 | **实际无效**：GuardedToolCallback 从 ToolContext 读，单例字段"看似在传、实际没人读"；高并发互相覆盖 |

**连锁症状**（代码实证）：
- 手递地狱：`TaskPlanner` 中 `ctx != null ? ctx.getConversationId() : ic.getConversationId()` 这类三元判断满天飞；`ChatController:202`、`TaskSnapshot:47`、`GuardedToolCallback:233`、`ApprovalServiceImpl` 均注释"异步线程无 UserHolder，须显式传 userId"
- 每次请求在 `AiServiceImpl` 里手动拼 `ChatContext.builder()...`，快照恢复时在 `ChatController.confirm()` 里再拼一遍（同一份数据两处构造）
- 观测层已有跨线程方案（`AgentTracer.resume(rootSpan)`），业务上下文却没有对等机制——同一个问题，观测解决了、业务没解决

---

## 2. 设计目标

1. **一个载体**：`AgentContext`（AI 会话上下文）贯穿整条链路，替代散落的 userId / conversationId / originalInput / history / rootSpan 手递
2. **创建点单一**：请求入口创建一次，全链路读取
3. **异步自动传播**：线程池装配 `TaskDecorator`，异步任务自动携带上下文，消除"异步丢 ThreadLocal"这一根本矛盾
4. **删除无效设计**：`ToolBeanCollector.conversationId` 单例状态
5. **可扩展**：`attributes` 承载阶段标记等临时信息，不再为每个新字段新增载体

---

## 3. 方案总览

```
┌─ 请求入口（ChatController.chat / confirm）
│   AgentContext ctx = AgentContext.builder()
│       .userId(UserHolder.getUserId())
│       .conversationId(conversationId)
│       .originalInput(content)
│       .history(chatMemory.get(conversationId))
│       .rootSpan(root)
│       .build();
│   AgentContextHolder.set(ctx);        // ① 同步段读取
│   ... 业务调用 ...
│   finally { AgentContextHolder.clear(); }
│
├─ 异步边界（CompletableFuture.runAsync(..., aiTaskExecutor)）
│   AgentContextPropagator（TaskDecorator）自动：捕获 → 执行前 set → finally remove
│
└─ 消费方（任何线程）
    AgentContext ctx = AgentContextHolder.get();   // ② 异步线程也能读到
    ctx.userId() / ctx.conversationId() / ...
```

**核心机制**：同步段 = ThreadLocal（现有 UserHolder 模式）；异步边界 = TaskDecorator 自动捕获/恢复（Spring 标准做法，对 `CompletableFuture.runAsync(r, executor)` 同样生效——它内部走 `executor.execute()`）。

---

## 4. 组件设计

### 4.1 `AgentContext`（值对象，`agent/context/AgentContext.java`）

```java
public final class AgentContext {
    private final Long userId;
    private final String conversationId;
    private final String originalInput;          // 原始用户输入（历史落库/快照恢复用）
    private final List<Message> history;         // 会话历史（Hook 链用，可空）
    private final AgentSpan rootSpan;            // 观测根 span（跨线程挂载用，可空）
    private final Map<String, Object> attributes;// 扩展点：阶段标记等

    // builder + 只读 getter；attributes 提供 put/get 便捷（不可变主字段 + 可变扩展区）
    // 提供 withOriginalInput(...) 等派生方法（快照恢复时替换原始输入）
}
```

设计要点：
- **主字段不可变**（final），扩展信息走 `attributes`（`ConcurrentHashMap`，线程安全）
- `history` 是引用不拷贝（会话历史量大，避免每请求复制）

### 4.2 `AgentContextHolder`（ThreadLocal 载体，`agent/context/AgentContextHolder.java`）

```java
public final class AgentContextHolder {
    private static final ThreadLocal<AgentContext> TL = new ThreadLocal<>();
    public static void set(AgentContext ctx) { TL.set(ctx); }
    public static AgentContext get() { return TL.get(); }
    public static AgentContext require() {   // 必填读取：缺失抛异常（防静默 NPE）
        AgentContext ctx = TL.get();
        if (ctx == null) throw new IllegalStateException("AgentContext 未初始化（异步边界未传播？）");
        return ctx;
    }
    public static void clear() { TL.remove(); }
}
```

### 4.3 `AgentContextPropagator`（TaskDecorator，`agent/context/AgentContextPropagator.java`）

```java
@Component
public class AgentContextPropagator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        AgentContext captured = AgentContextHolder.get();   // 提交线程捕获（可空 = 无上下文的任务）
        return () -> {
            AgentContextHolder.set(captured);               // 执行线程恢复
            try {
                runnable.run();
            } finally {
                AgentContextHolder.clear();                 // 必须清理：防线程复用污染下一个任务
            }
        };
    }
}
```

**装配**：`AgentConfig` 的两个线程池加 `executor.setTaskDecorator(agentContextPropagator)`（aiTaskExecutor / subtaskExecutor）。

### 4.4 创建与清理点（`agent/controller/ChatController.java`）

| 路径 | 创建 | 清理 |
|------|------|------|
| `POST /agent/string/send`（JSON/SSE） | `AgentContextHolder.set(...)`（在 `chat()` 入口，userId 从 UserHolder 取） | `chat()` 的 finally（与现有 `rootSpan.closeRootScope()` 同点） |
| `POST /agent/confirm`（SSE 续流） | 从 `AgentApproval` 重建（替代现有手拼 `ChatContext.builder()`） | confirm 方法 finally |

> 清理必须与创建成对；异步线程由 Propagator 清理，不依赖请求线程。

---

## 5. 与现有机制的关系（迁移矩阵）

| 现有机制 | 处置 |
|----------|------|
| `UserHolder` | **保留**（全局认证上下文，拦截器/Controller 用）。agent 链路内部改用 AgentContext；异步段不再依赖 UserHolder |
| `ChatContext` | **目标态：并入 AgentContext**（ChatContext 的字段 AgentContext 全有）。分两步：第一步 AgentContext 新建、`PromptHookExecutor` 从 AgentContext 构建 ChatContext；第二步删 ChatContext，Hook 链签名改 AgentContext |
| Spring AI `ToolContext` | **保留**（Spring AI 工具参数机制）。`GuardedToolCallback` 的 userId/conversationId 优先从 ToolContext 读，兜底改从 `AgentContextHolder` 读（替代现在的构造值兜底） |
| `ToolBeanCollector.conversationId` | **删除**（含 `AiServiceImpl` 两处 `setConversationId` 调用；GuardedToolCallback 的 conversationId 来源改 Holder，构造参数保留为兜底） |
| `TaskSnapshot` | 精简：rootSpan 改从 AgentContext 读（快照不落库根 span，恢复时用新根）；userId/conversationId 字段保留（**跨线程持久化载体**，DB 落库语义，与请求级 AgentContext 互补） |
| `AgentTracer.resume(rootSpan)` | **保留**（观测层机制不动）；rootSpan 由 AgentContext 携带，替代各处手递 |
| `SubTaskExecution`/`SubTaskPlan` | **保留**（子任务执行数据）；`SubTaskPlan` 手递的 userId/conversationId 取消，改从 AgentContext 读（保留字段作持久化兜底） |

**边界定义**（写入代码注释）：
- `AgentContext` = **请求级**上下文（一个请求一条链路）
- `TaskSnapshot`/`AgentApproval` = **跨请求持久化**上下文（CONFIRM 恢复用，必须显式落库）
- `SubTaskExecution`/`SubTaskPlan` = **任务级**上下文（子 Agent 要执行什么）
- `UserHolder` = **Web 请求**认证上下文（不进异步）

### 5.1 子 Agent 的上下文（两层模型）

子 Agent 执行天然跨异步线程，上下文分两层，各司其职：

```
父级（请求级）AgentContext —— TaskDecorator 自动传播到 subtaskExecutor 线程
  ├─ userId / conversationId / originalInput / rootSpan
  └─ SubTaskAgent / SubAgentToolLoop 执行线程内 AgentContextHolder.get() 直接可读
     （SubTaskPlan 手递 userId/conversationId 的代码取消，字段保留作持久化兜底）

子级（任务级）SubTaskExecution / SubTaskPlan —— 显式随任务传递
  ├─ userInput / currentResponse / tasks / callback / properties
  └─ "我要执行什么"（任务数据），与"我是哪个请求的"（父级上下文）分离
```

要点：
1. **父级上下文自动可得**：`TaskPlanner.planAndExecuteAsync` 提交到 `subtaskExecutor` 时由 Propagator 捕获，`SubTaskAgent.execute()` 内 `AgentContextHolder.get()` 即父请求上下文——无需给 SubTaskPlan 加字段
2. **CONFIRM 恢复路径一致**：`confirm()` 入口从 `AgentApproval` 重建 AgentContext 后，`resumeFromSnapshot` 链路（同样走 subtaskExecutor）自动携带
3. **未来扩展**：子 Agent 需要更多父级数据（完整会话历史、用户资料、权限上下文）→ 从 AgentContext 读取或加入 attributes，不再扩散到 SubTaskPlan

---

## 6. 分步实施计划

### 第 1 步：骨架落地（低风险，纯新增 + 装配）—— ✅ 已实施

- [x] 新增 `AgentContext` / `AgentContextHolder` / `AgentContextPropagator`
- [x] `AgentConfig` 两个线程池装配 TaskDecorator
- [x] `ChatController.chat()` / `confirm()` 创建 + finally 清理
- [x] `ToolBeanCollector.conversationId` 删除（`AiServiceImpl` 两处调用删除，GuardedToolCallback 兜底改 Holder）
- [x] 编译 + 单测验证（`AgentContextHolderTest` / `AgentContextPropagatorTest` 8 用例全绿；行为不变：所有读取点仍走原路径，只是新增了可用来源）

### 第 2 步：消费方迁移（中风险，逐步替换手递）
- `PromptHookExecutor`：从 `AgentContextHolder` 构建（`ChatContext.builder()` 数据来源统一）
- `TaskPlanner`：`ctx != null ? ... : ...` 三元收敛——同步段传 ChatContext、异步段从 Holder 读
- `GuardedToolCallback`：conversationId 兜底改 `AgentContextHolder.get()`
- `ChatController.confirm()`：重建上下文改用 AgentContext（替代手拼 ChatContext）
- 每处迁移独立提交，编译 + 单测

### 第 3 步：ChatContext 并入（目标态）
- `ChatContext` 删除，Hook 链接口（`PromptHookChain`/`AfterAiHookChain`/`TaskTriggerHook`）签名改 `AgentContext`
- `TaskSnapshot` rootSpan 改从 AgentContext 读
- 清理 `AgentContextHolder.require()` 的缺失路径（快照恢复无请求线程场景显式传参）

---

## 7. 风险与权衡

| 风险 | 缓解 |
|------|------|
| **ThreadLocal 传播的清理遗漏** → 线程复用污染下一任务 | Propagator 的 finally clear 是硬约束（单测覆盖：任务执行后 Holder 为空）；`require()` 缺失即抛错，把静默错误变显式 |
| **隐式依赖**：代码不知道上下文从哪来 | 边界写入注释 + 文档：同步段显式、异步边界自动；`require()` 兜底防 NPE |
| **快照恢复路径无请求线程**（confirm 续流异步执行） | AgentContext 在 confirm 入口创建（有请求线程），异步段由 Propagator 传播，语义一致 |
| **CompletableFuture 内部再套 CompletableFuture** | 只要都走装配了 Propagator 的 executor 即传播；不走 executor 的（如 `thenApply` 同线程）自然继承 |
| **历史消息拷贝开销** | `history` 只存引用不拷贝 |

---

## 8. 验证方案

1. **单测**：`AgentContextPropagatorTest`（提交时捕获/执行恢复/finally 清理/嵌套任务）、`AgentContextHolderTest`（require 缺失抛错）
2. **编译**：build-tmp 全量编译
3. **链路验证（VM）**：SSE 对话 + CONFIRM 审批续流 + 工具调用（Langfuse 检查 round/tool_call span 仍挂会话树、guard span 的 conversationId 正确）
4. **回归**：JSON 模式、`feature.subagent.enabled=false` 回退路径、`feature.tool-routing.enabled=false` legacy 路径

---

## 9. 待确认决策点

1. **ChatContext 是否最终并入 AgentContext**（第 3 步）——并入是目标态但改动面大（Hook 链接口签名）；也可保留 ChatContext 作为 Hook 专用视图，仅统一数据来源
2. **`AgentContext.require()` 的失败策略**：抛异常（Fail-Fast）还是返回 null（Fail-Open）——建议 Fail-Fast（异步传播是机制承诺，缺失即 bug）
3. **attributes 是否纳入观测**（Langfuse span 属性透传）——建议暂不纳入，保持上下文与观测分离
