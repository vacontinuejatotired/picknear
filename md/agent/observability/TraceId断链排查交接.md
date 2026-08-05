# ✅ 已解决：会话树 traceId 断链问题（交接文档）

> **状态**：已解决（2026-08-03，集成测试全绿验证闭环）
> **创建**：2026-08-03
> **影响**：业务功能正常，观测树无法串起（一次流程多个 traceId）
> **上一篇**：[Langfuse云接入说明](./Langfuse云接入说明.md)、[Agent全链路观测架构设计](./Agent全链路观测架构设计.md)
> **完整排查过程**：[TraceId断链排查流程](./TraceId断链排查流程.md)（思考流转/验证/事后感）

---

## 1. 问题现象

一次完整 AI 流程（如"长沙天气"：Phase1 → 规划 → 子 Agent → 工具调用）产生的所有 span **各占一个 traceId**，Langfuse 控制台搜任意 traceId 只能看到单个环节，**整棵树串不起来**。

生产（VM 容器）与本地集成测试均可复现。

## 2. 根因（双层次，均已被字节码 + 实测实锤）

### 根因一（主根因，agent.* 全断）：生命周期时序错误

`AgentTracer.start()` 原顺序为 **createNotStarted → openScope → start**。

micrometer-tracing 1.4.4 的机制（`TracingObservationHandler` 字节码验证）：

```
openScope() → onScopeOpened → getTracingContext()
    └─ context.computeIfAbsent(TracingContext.class, ...)  ← 把【空 TracingContext】塞进 observation 的 context
start() → onStart → getParentSpan(context)
    └─ 第一行 context.get(TracingContext.class) → 【非 null（上一步塞的）】
       → 情况A：直接返回自己的 span（null），【父级被短路】
       → tracer.nextSpan() 无父 → 新 traceId
```

**每个 span 的父级在 onStart 时被自己的空 TracingContext 短路** → 全部成为新 trace 根。session 是根（parent=null 本来就正确）所以"看起来正常"，子 span 全断。

**修复**：改为官方 `Observation.start()` 封装的标准顺序——**先 start（此时自己 context 尚无 TracingContext，getParentSpan 正常走父级分支），后 openScope（TracingContext 已含 span，onScopeOpened 正常挂线程）**。

```java
// AgentTracer.java start() 内
observation.start();                          // 先
Observation.Scope scope = lifecycle.openScope(observation);  // 后
```

> 教训：`Observation.start(name, registry)` 静态方法（= createNotStarted + start）是官方惯例，本项目手写 create→openScope→start 时颠倒了 start 与 openScope 的顺序。

### 根因二（流式 chat 断链）：spring.ai ChatClient 层观察对象污染 Reactor context

修复根因一后，同步 `call()` 的 chat span 全部挂上，但 **phase1 的流式 `stream()` chat span 仍断链**。字节码分析（DashScopeChatModel.internalStream + DefaultChatClient$DefaultStreamResponseSpec）：

1. ChatClient 层有自己的观察对象（`spring.ai.chat.client`），创建后 `contextWrite` 把它写进 Reactor context（key=`"micrometer.observation"`，与 `ObservationThreadLocalAccessor.KEY` 一致）
2. model 层观察对象（`chat qwen-plus`）从 Reactor context 读父 → 读到 **ChatClient 层观察对象**
3. ChatClient 层观察对象被 handler 链上 **meter 优先的 `TracingAwareMeterObservationHandler`（composite 第一个）匹配 → 不创建 tracing span / TracingContext**
4. model 层的 `getParentSpan`：父 observation 存在但 context 无 TracingContext → 返回 null → **新 traceId**

**修复**：绕开 ChatClient 层，phase1 流式直接调 `DashScopeChatModel.stream(prompt)`（model 层），并把当前线程栈顶 observation（phase1）通过 `contextWrite` 写入 Reactor context（model 层自身的 contextWrite 在观察对象创建之后执行，不污染父级读取）：

```java
// AiServiceImpl.java phase1 流式调用
Observation streamParent = observationRegistry.getCurrentObservation();
Flux<ChatResponse> stream = dashScopeChatModel.stream(streamPrompt);
if (streamParent != null) {
    stream = stream.contextWrite(rctx -> rctx.put("micrometer.observation", streamParent));
}
for (String token : stream.map(r -> r.getResult().getOutput().getText()).toIterable()) { ... }
```

## 3. 验证闭环（集成测试全绿）

`AgentTracerIntegrationTest` 最终断言结果（一次"长沙天气"完整流程）：

```
agent.session (根)
├─ agent.prompt_hook   ← session ✓
├─ agent.phase1        ← session ✓
│  ├─ agent.decision   ← phase1 ✓（原断言误写 session，已修正为 phase1：decision 在 phase1 scope 内创建）
│  └─ chat qwen-plus   ← phase1 ✓（流式修复生效）
├─ agent.round.1       ← session ✓
│  ├─ agent.plan       ← round.1 ✓
│  │  └─ chat qwen-plus ← plan ✓
│  └─ agent.subagent   ← round.1 ✓
│     ├─ tool_call query-weather ← subagent ✓
│     │  └─ agent.guard.* ← tool_call ✓
│     └─ chat qwen-plus ×2 ← subagent ✓
```

Tests run: 1, Failures: 0, Errors: 0。所有 span 同 traceId，Langfuse 树形结构恢复。

## 4. 排查过程踩过的坑（防止重蹈）

| # | 坑 | 说明 |
|---|-----|------|
| 1 | 交接文档 §4.3 的"核心矛盾"是错的 | 手动 probe 路径"能取到父级"仅指 observation 层（未 start 未导出 span），OTel 层一旦 start 同样断链（实测 parent=全零） |
| 2 | `getObservationHandlers()` 非公共 API | micrometer 1.14.5 是 package-private，编译失败；需 `getDeclaredMethod` + `setAccessible(true)` |
| 3 | "测试已带诊断代码"从未成功跑过 | 交接方标注"代码已写好未跑"，实际编译失败（A 项代码首次执行即编译错误） |
| 4 | Reactor contextWrite 方向 | 下游 contextWrite 的值被**更上游**的 contextWrite 覆盖；且订阅时**从下游向上**执行，上游 deferContextual 里创建的闭包变量在更下游 contextWrite 执行时还是 null（三层实验实锤，put(null) 直接 NPE） |
| 5 | 同步/流式断链机制不同 | `call()` 走 `Observation.observe()`（标准链路，无覆盖）；`stream()` 有 Reactor context 显式覆盖逻辑 |
| 6 | LLM 诊断过滤名写错 | 过滤 `spring.ai.`/`gen_ai.` 前缀，实际 span 名是 `chat qwen-plus`（自定义 convention），诊断一直没生效 |

## 5. 变更文件清单

```
src/main/java/com/hmdp/agent/observability/api/AgentTracer.java   # 修复一：start 先于 openScope（+注释）
src/main/java/com/hmdp/agent/service/impl/AiServiceImpl.java      # 修复二：流式绕开 ChatClient 层 + contextWrite 传父
src/test/java/com/hmdp/agent/observability/AgentTracerIntegrationTest.java  # decision 断言修正 + 判别实验 + 诊断打印
md/agent/observability/TraceId断链排查流程.md                     # 完整排查记录（本系列新增）
```

## 6. 遗留事项

- 测试中的 `[诊断]` 打印与 probe 判别实验（错误时序对照）可保留（对后续排查有用），如需精简为正式断言再另行处理
- 生产部署前建议跑一次真实链路确认 Langfuse 控制台树形结构（本地 InMemory 已验证）
