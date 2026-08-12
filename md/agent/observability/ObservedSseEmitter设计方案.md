# ObservedSseEmitter 设计方案（审查后定稿版 v2）

> **状态**：三子模型对抗审查完成，修订后待实施（2026-08-04）
> **背景**：[TraceId断链排查交接](./TraceId断链排查交接.md) 的后续：根 span 导出规范化
> **审查**：3 个子模型并行对抗审查（生命周期/连接安全、并发安全、集成回归），本文档已吸收全部 HIGH/MEDIUM 问题

---

## 0. 审查结论摘要（三个视角合并，去重后）

### 必须修（阻断实施）
| # | 问题 | 来源 | 影响 |
|---|------|------|------|
| R1 | **根 scope 跨线程泄漏 + 异线程 scope.close() 污染** | 生命周期+并发 | Tomcat 线程复用 → **下一个会话根 span 挂到旧会话下（跨会话 trace 污染）**；调度线程/业务线程被覆写 current scope |
| R2 | **容器 onError 未注册 → finish 属性丢失/竞态** | 并发+集成 | 容器错误路径无人 finish；Controller 裸 end() 先 stop，finish 属性永不导出 |
| R3 | **创建顺序**：emitter 先于 root → root=null 静默降级 | 并发 | 精确复现原始事故（session 永不结束）且无日志 |
| R4 | **guard 只 finish 不 complete → async 僵尸** | 生命周期 | 极端场景连接+async context 无限占用 |
| R5 | **SseUtils.safeSend 只 catch IOException**：终态后 send 抛 ISE | 生命周期 | 业务误判"流式失败"→ 最多 3 次无意义 LLM 重试（浪费 token） |
| R6 | **临时诊断代码无清理计划**（AgentTracer System.out + 15 处 OBS-DIAG） | 集成 | 生产每请求刷日志 |

### 应当修（随实施同步）
| # | 问题 | 来源 |
|---|------|------|
| R7 | 错误路径全用 complete() → finish=COMPLETE 误标 | 集成 |
| R8 | finish() 无日志 → 验收标准不可执行 | 集成 |
| R9 | 测试走老路径（手动 end），无 Controller/HTTP 级用例 | 集成 |
| R10 | route/TaskPlanner 显式 root.end() 未纳入收敛清理 | 集成 |
| R11 | JSON 分支（chatReturnStringResult）无 session 根，范围未声明 | 集成 |
| R12 | root=null/Noop 时子 span 误挂陌生父 | 并发 |
| R13 | guard 引用持有（有界，可接受）| guardDelayMs 需校验 > timeoutMs | 并发 |

### 低（随文档声明）
L1 包位置分层冲突；L2 TaskScheduler 风险措辞 + pool.size 配置；L3 finish() root 判空；L4 AiServiceImpl:220 complete() 未 try/catch；L5 构造失败窗口降级；L6 ToolBeanCollector.conversationId 共享（预存，非本次）。

---

## 1. 问题背景

### 1.1 断链事实（生产实测，已字节码证实）

```
emitter.complete() 调用成功 → onCompletion 回调未触发（客户端断开时
WebAsyncManager.setConcurrentResultAndDispatch 检测 isAsyncComplete()==true 直接 return，
不 dispatch → onCompletion 永不触发）→ root.end() 未执行 → session 永不 stop/导出
→ LogView 全部子 span 平铺（parentSpanId 指向不存在的 session）
```

### 1.2 第二个断链源（审查新发现）：根 scope 跨线程泄漏

`startSession` 在 **Tomcat 请求线程** openScope（根 scope 挂到请求线程 ThreadLocal），而 `root.end()` 永远在**别的线程**（异步业务线程/容器线程/调度线程）执行——`SimpleScope.close()` 只操作**关闭者**线程的 ThreadLocal，**请求线程的根 scope 永不 pop**。后果：
1. 池化 Tomcat 线程复用 → 下一个请求 `createNotStarted` 捕获到**上一个会话的根** → **跨会话 trace 污染**（新会话挂到旧会话下）
2. 异线程 `scope.close()` 覆写**关闭者**线程的 current scope → 该线程后续 span 失父/错父

**这是独立于 onCompletion 的根因层漏洞，本次一并修复。**

---

## 2. 设计目标

1. 根 span **必然结束**（正常/异常/超时/断开/兜底，任何路径收敛）
2. 根 span **只结束一次**（幂等 + only-once reason）
3. **连接安全**：SSE 生命周期与会话强绑定，断开/超时/并发 complete 均正确
4. **并发安全**：多线程无竞态、无泄漏、**无跨会话污染**
5. 可观测：finish 原因可追溯；结束路径有日志

---

## 3. 类设计（ObservedSseEmitter，含审查修订）

```java
package com.hmdp.agent.observability.api;   // L1：放 api 包，遵守"主链路只 import api"分层约定

public class ObservedSseEmitter extends SseEmitter {

    private final AgentSpan root;
    private final AtomicReference<String> finishReason = new AtomicReference<>(null);
    private final ScheduledFuture<?> timeoutGuard;

    public ObservedSseEmitter(long timeoutMs, AgentSpan root,
                              TaskScheduler scheduler, long guardDelayMs) {
        super(timeoutMs);
        // R2：容器超时 + 容器错误 都收敛到 finish（注册顺序在 Controller 之前，先执行）
        super.onTimeout(() -> finish("TIMEOUT"));
        super.onError(ex -> finish("ERROR"));
        // R4：guard 同时结束 span 与 emitter（trySetComplete CAS 幂等，无竞争风险）
        this.timeoutGuard = scheduler != null
                ? scheduler.schedule(this::finishGuard, Instant.now().plusMillis(guardDelayMs))
                : null;   // L5：schedule 抛异常时在此 catch 降级为 null
    }

    @Override public void complete()                      { finish("COMPLETE"); super.complete(); }
    @Override public void completeWithError(Throwable ex) { finish("ERROR");    super.completeWithError(ex); }

    private void finishGuard() {
        finish("TIMEOUT");
        try { super.complete(); } catch (Exception ignored) {}   // R4：防 async 僵尸（CAS 幂等）
    }

    private void finish(String reason) {                    // R8：加日志
        if (finishReason.compareAndSet(null, reason)) {
            if (timeoutGuard != null) timeoutGuard.cancel(false);   // L3：cancel 先行，防 guardDelayMs=0 竞态
            if (root != null) {                              // L3：root 判空
                root.set(AgentField.FINISH, reason);
                root.end();                                  // AgentSpanImpl.end() 内部做属主线程 scope 处理（R1）
            }
            log.info("SSE 会话结束 reason={} thread={}", reason, Thread.currentThread().getName());
        }
    }
}
```

### 3.1 关键时序（已字节码验证）

- **先 finish 后 super.complete()**：super.complete() 抛异常（窄竞态 ISE）时 span 已结束；emitter 留待容器超时兜底完成
- **set 在 end 前**：`SimpleObservation.lowCardinalityKeyValue` 无 stopped 检查，stop 后写入被丢弃——先写属性保证 finish 恒为首个 winner 值
- **onTimeout/onError 追加注册**（`addDelegate`），包装类先注册先执行；Controller 保留日志不冲突
- **finishReason CAS + AgentSpanImpl.end() AtomicBoolean 双幂等**：多线程竞争 only-once

---

## 4. 配套改造（R1/R5/R10 等，与类并行实施）

### 4.1 AgentSpanImpl：根 scope 属主线程处理（R1，核心）

```java
public class AgentSpanImpl implements AgentSpan {
    private final Observation observation;
    private final Observation.Scope scope;      // openScope 时的 scope
    private final Thread scopeOpener;           // 记录属主线程（openScope 所在线程）
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final AtomicBoolean scopeClosed = new AtomicBoolean(false);

    @Override public void end() {
        if (ended.compareAndSet(false, true)) {
            observation.stop();                 // stop 与线程无关，照常
            closeScopeSafely();                 // 仅属主线程 close；异线程标记待关
        }
    }

    /** 新增（接口 AgentSpan 同步加方法，NoopAgentSpan 提供 no-op 实现）：属主线程显式关闭根 scope */
    @Override public void closeRootScope() {
        closeScopeSafely();
    }

    private void closeScopeSafely() {
        if (scopeClosed.compareAndSet(false, true)) {
            if (Thread.currentThread() == scopeOpener) {
                try { scope.close(); } catch (Exception ignored) {}
            }
            // 非属主线程调用：忽略（幂等）——scope 由属主线程的 finally 负责关闭（见 4.2）
        }
    }
}
```

### 4.2 AiServiceImpl：同步段 finally 关闭根 scope（R1 配套）

```java
// doChatWithToolcall 内：同步段（prompt_hook/决策）全程在请求线程持有 root scope，
// 无论正常还是异常，进入异步段前都必须在请求线程显式关闭（防 Tomcat 线程泄漏 + 跨会话污染）。
// 放 finally 保证异常路径也不漏；异步段 resume(rootSpan) 重新 openScope，不依赖原 scope。
try {
    ...同步段（prompt_hook、processHookResult 决策）...
} finally {
    if (rootSpan instanceof AgentSpanImpl impl) {
        impl.closeRootScope();
    }
}
```

> 注意：`closeRootScope()` 只在属主线程（请求线程）调用才实际 close；非属主调用幂等忽略。

### 4.3 SseUtils.safeSend：catch 拓宽（R5）

```java
// 原：catch (IOException)
catch (IOException | IllegalStateException e) { /* 终态后 send 静默失败，业务不误判 */ }
```

### 4.4 错误出口语义（R7）

- 保留错误路径 `complete()`（先发错误事件再 complete，前端能收到）；finish=COMPLETE 语义在文档声明（错误事件已推送，finish 表示"流正常结束"）
- `completeWithError` 仅在 TaskPlanner 异常路径使用（finish=ERROR）

### 4.5 Controller 创建点（R3 顺序）

```java
AgentSpan root = agentTracer.startSession(...);        // 先
SseEmitter emitter = new ObservedSseEmitter(SSE_TIMEOUT, root, taskScheduler, SSE_GUARD_TIMEOUT);  // 后
// 三回调：只留日志（onCompletion/onTimeout/onError 均删 attribute + end，收敛到包装类）
```

### 4.6 清理计划（R6/R10）

- 删除 `AgentTracer.start()` 内 `[OBS-DIAG]` System.out 诊断块
- 删除 `AiResponseRouter.endRootSpan()` 及全部 `[OBS-DIAG]` 日志
- 删除 `TaskPlanner` 内两处显式 `rootSpan.end()` + `[OBS-DIAG]`（收敛由包装类唯一负责）
- `ChatController` 三回调内 `[OBS-DIAG]` 日志删除（保留正常 INFO/WARN 日志）

---

## 5. 参数决策

| 参数 | 值 | 依据 |
|------|-----|------|
| `SSE_TIMEOUT` | 30min（不变） | 现状 |
| `SSE_GUARD_TIMEOUT` | 32min | 容器超时(30min)先触发 onTimeout→finish(TIMEOUT)；guard 为最后防线，须 > timeoutMs（R13 校验） |
| finish reason | COMPLETE/TIMEOUT/ERROR | 与现状对齐；断开归 COMPLETE（业务已完成的正常语义） |
| TaskScheduler 注入 | `@Autowired(required=false)` / ObjectProvider（L2） | Boot 3.4.4 必有 bean（javap 证实），required=false 仅为降级保险 |
| `spring.task.scheduling.pool.size` | 建议 2-4（L2） | Boot 默认 1，guard 任务共享单线程 |

---

## 6. 测试计划（R9 修订）

**范围声明**：本次方案仅覆盖 **SSE 模式**（`/agent/string/send` + `Accept: text/event-stream`）。**JSON 模式**（`chatReturnStringResult`，无 SSE、不创建 session 根）为 out-of-scope，验收标准不覆盖。

| 用例 | 级别 | 验证点 |
|------|------|--------|
| wrapper 单测：complete→root 已 end、双 complete only-once、complete‖error 并发 first-wins、guard 触发、root=null 降级 | 单测 | CAS/幂等/降级 |
| **AgentTracerIntegrationTest 改走 ObservedSseEmitter**，去掉手动 root.end()，断言"不手动 end 也必然结束" | 集成 | 包装类实际接管 |
| **Controller 级测试**（MockMvc `Accept: text/event-stream`）：PASS→finish=COMPLETE、PLANNING→complete、init 异常路径 | 集成 | 三回调收敛无回归 |
| **同一线程连续两次请求，traceId 不同且都是真根**（R1 回归） | 集成 | 跨会话污染已修 |

---

## 7. 验收标准

1. 生产一次完整请求（PASS 与 PLANNING）→ Langfuse 树形正确（session 为根，所有子 span 同 traceId）
2. 断开连接/刷新页面 → 无"永不结束的 session"（日志 `SSE 会话结束 reason=TIMEOUT` 可验证）
3. 无跨会话污染：连续两次请求 traceId 独立、根都是真根
4. 生产日志无 `[OBS-DIAG]`/System.out 诊断残留

---

## 8. 第二轮评审追加（生命周期专项，2026-08-04）

> 本轮针对**生命周期**逐场景排查，并**反编译 Spring 6.2.5 字节码**（`ResponseBodyEmitter`/`SseEmitter`/`DeferredResult`/`WebAsyncManager`）验证设计对框架行为的假设。以下先给已验证的框架事实，再给新发现清单。原 R1–R13 已吸收，本轮为增量。

### 8.1 已验证的框架事实（字节码依据，非背书）

| # | 事实 | 依据 | 对设计的影响 |
|---|------|------|-------------|
| V1 | `onTimeout`/`onError`/`onCompletion` 都是 `addDelegate` **追加链**，按注册顺序执行（非覆盖） | `ResponseBodyEmitter$TimeoutCallback/ErrorCallback/CompletionCallback.addDelegate` | 设计声称"包装类构造器先注册先执行"**成立** ✓ |
| V2 | `complete()`/`completeWithError()` 内部 `trySetComplete()`（state CAS START→COMPLETE 或 TIMEOUT→COMPLETE）；state 已终态时**静默 no-op，不抛 ISE**。**抛 ISE 的是 `send()`**（`assertNotComplete()`） | `ResponseBodyEmitter.complete/trySetComplete/assertNotComplete` 字节码 | **§3.1"super.complete() 抛异常（窄竞态 ISE）"是事实错误**；R5 对 `send` 的 ISE 捕获正确 |
| V3 | 三回调 **state 守卫互斥**：`TimeoutCallback`=CAS(START→TIMEOUT)；`ErrorCallback`/`CompletionCallback`=CAS(START→COMPLETE)，失败即不执行委托 | 三个回调 `run()/accept()` 字节码 | ① 超时后（state=TIMEOUT）onCompletion/onError **再也不会触发**；② 正常 complete 后（state=COMPLETE）onTimeout 不会再触发。**设计不依赖 onCompletion，方向正确**，但文档须写明互斥语义 |
| V4 | 容器异步超时从 `startAsync` 起算（controller 返回后 returnValueHandler 才 `startAsync`），**guard 却从构造起算** | `WebAsyncManager.startDeferredResultProcessing` → `AsyncContext.setTimeout` | 两把钟**不同基准**，见 M-2 |
| V5 | 客户端断开时容器完成 async → **超时被取消**，onTimeout 不触发；此场景 guard 是唯一兜底 | 原断链事故 + V3 | guard 必要性成立，但**降级（scheduler=null）时该兜底消失**，见 M-3 |

### 8.2 新发现清单（增量，按严重度）

#### 必须修（阻断实施）

**N1 — `finish()` 非异常安全：`root.set()` 抛异常 → `root.end()` 跳过，span 永久泄漏**

```java
if (finishReason.compareAndSet(null, reason)) {
    if (timeoutGuard != null) timeoutGuard.cancel(false);
    if (root != null) {
        root.set(AgentField.FINISH, reason);   // ← 若抛异常
        root.end();                          // ← 永不执行；CAS 已置位，无任何路径重试
    }
    log.info(...);
}
```

`finish()` 是整棵树**唯一**的终态漏斗，一旦 CAS 置位后中途抛异常，`finishReason` 已非 null → 其他路径全部空转 → span 永不 stop，**直接违反设计目标 1（根 span 必然结束）**。当前 `set(AgentField.FINISH, …)` 具体抛异常路径罕见（sanitizer/KeyValue 对固定字符串不会炸），但终态漏斗按构造就应异常安全。**修法**：`try { root.set(...) } finally { root.end() }`（end 必执行）。

**N2 — R10 清理是承重而非装饰：非 funnel 的 `root.end()` 先执行 → `finish` 属性静默丢失**

只要残留任何非 funnel 的显式 `root.end()`（TaskPlanner:108 / AiResponseRouter `endRootSpan`），且它先于 `finish()` 执行，则 span 已 stop，`finish()` 里的 `set(AgentField.FINISH, …)` 写入被丢弃（V/§3.1 自证："stop 后写入被丢弃"）→ **finish 原因丢失**。这与 R10 是否"顺便删掉"无关，是**必须原子完成**：包装类上线时同步清掉全部显式 end，并加一条集成断言"finish 属性恒存在"。若清理不彻底，验收标准 1/2 的"reason 可验证"会静默失效。

#### 应当修（随实施同步）

**M-1 — R13 校验没落进构造器：guardDelayMs ≤ timeoutMs 时正常流被 guard 提前掐断**

R13 只在评审表，§3 构造器代码无校验。误配置（如 guardDelayMs=5min）→ guard 先于容器超时 → `finish("TIMEOUT")` + `super.complete()` **掐断健康流**。这是毫无回报的破坏性失败，构造器应 fail-fast：`if (guardDelayMs <= timeoutMs) throw new IllegalArgumentException(...)`。

**M-2 — guard 与容器超时时钟基准不一致**

guard 从**构造**起算（32min），容器超时从 **startAsync** 起算（30min）。同步段（prompt_hook/chatMemory/meta send/chatWithToolcall 初始化）耗时 > 2min 时 guard 先触发，`finish("TIMEOUT")` + complete 提前断流。当前同步段秒级，风险低，但设计以"30 < 32"为前提，须显式声明前提（或把 guard 延迟改为"容器超时之后"触发，如 onTimeout 注册后再 schedule 补刀）。

**M-3 — L5 降级时"根 span 必然结束"保证失效（原文档低估）**

guard 是"客户端断开 + 业务挂死"场景的**唯一**兜底（V5：断开→容器取消超时→onTimeout 不触发→若业务又挂死，只有 guard 收场）。`scheduler==null`（schedule 抛异常降级）时该兜底消失，且 `finishReason` 永不置位 → span 永久泄漏。降级语义须在文档声明为**保证降级**，或 fail-fast 拒绝构造（既然 Fail-Open 哲学是"埋点不影响业务"，更合理是：scheduler 拿不到就**不创建包装类、退回旧 SseEmitter**，把保证降级显式化，而不是半吊子 new 一个无 guard 的包装类）。

**M-4 — 断开语义矛盾：§5 决策表"断开归 COMPLETE"与 onError 实现冲突**

客户端 abort 若被容器作为 async error 上报 → `ErrorCallback.accept()` → 包装类 `finish("ERROR")` → 记录 ERROR；只有 safeSend 吞掉 IOException 的路径才归 COMPLETE。同一"断开"两种结果，取决于容器时机。文档应承认：**断开不是单一 reason**（COMPLETE 或 ERROR 皆可能），决策表措辞修正，验收别断言断开必 COMPLETE。

**M-5 — `closeRootScope()` 声明"幂等"但实现无状态位，且隐含 LIFO 前置**

`closeScopeSafely()` 无 closed 标志：二次调用会重复 `scope.close()`（可能 pop 错 scope）；且 `scope.close()` 语义要求**根 scope 在调用时为线程栈顶**（close 非按引用、按栈顶）。当前 closeRootScope 置于同步段末尾（prompt_hook 已 try-with-resources 关闭，栈顶恰为根）恰好安全，但这是**脆弱的顺序耦合**：若将来同步段新增未关闭的 span，栈顶不再是根 → 关闭错位。加 closed 状态位 + 文档写明 LIFO 前置。

#### 低（随文档声明）

- **L-1** 超时/错误后 `onCompletion` 永不触发（V3 state 守卫）：Controller 的 onCompletion 日志在 TIMEOUT/ERROR 路径不会打。验收标准 2 依赖的是 `finish()` 日志（正确），但需在文档写明"onCompletion 日志在超时/错误路径缺失属预期"，避免误判日志丢失。
- **L-2** 超时后业务线程继续流式 → `route()` 内的**裸 `emitter.send()`**（AiResponseRouter:75/83/97/107）抛 ISE → 走 route 的 catch → 打"AiResponseRouter 异常"噪声日志 + 二次 complete。建议 route 内 send 也统一走 `safeSend`（R5 只覆盖了 safeSend，漏了裸 send）。
- **L-3** §3.1 事实修正（V2）：`super.complete()` 不抛 ISE，guard 的 try/catch 属冗余但无害；R5 的 ISE 来源于 `send()` 而非 `complete()`。措辞修正即可，不影响正确性。
- **L-4** guard 任务共享 `TaskScheduler`（Boot 默认 pool.size=1）：超时高峰时 guard 排队延迟 → 兜底变"迟到"。L2 已建议 2-4，确认即可。

### 8.3 生命周期场景全列举（排查表）

| # | 场景 | 触发线程 | 终态序列 | 结论 |
|---|------|---------|---------|------|
| C1 | 正常完成（PASS/REPLACE/BLOCK/PLANNING 完成） | 业务线程 | `complete()`→finish(COMPLETE)→attribute→end；super.complete()→容器完成 | ✓ 收敛；若 R10 残留 root.end() 先跑则 attribute 丢失（N2） |
| C2 | 正常完成 + 客户端已断开 | 业务线程 | `complete()`→finish(COMPLETE)（不依赖 onCompletion）| ✓ 修复原事故核心路径 |
| C3 | 容器超时（30min） | 容器线程 | onTimeout→finish(TIMEOUT)→attribute→end；state→TIMEOUT→容器撕连接 | ✓ 收敛；onCompletion 不触发属预期（L-1）；业务线程继续流式至 send ISE（R5/L-2）|
| C4 | 客户端断开 → 容器 async error | 容器线程 | onError→finish(ERROR)→attribute→end | ✓ 收敛；reason=ERROR 与决策表冲突（M-4）|
| C5 | 客户端断开 + 业务挂死 + 容器不回调 | —（无回调）| guard(32min)→finish(TIMEOUT)→super.complete() | ✓ 唯一兜底；scheduler=null 时泄漏（M-3）|
| C6 | 业务 complete ‖ 容器超时 并发 | 业务+容器 | finish CAS 一者胜；另一方 super.complete() 静默 no-op（V2）| ✓ 幂等；reason 取先到者 |
| C7 | guard ‖ complete 并发 | guard+业务 | finish CAS 一者胜；guard 的 super.complete() trySetComplete no-op | ✓ 幂等 |
| C8 | 同线程连续两次请求（R1 回归） | Tomcat 复用 | 第一请求 closeRootScope 关根 scope → 第二请求 createNotStarted 取不到旧根 | ✓ 前提是 4.2 的 closeRootScope 在**每个**路径都执行（含 BLOCK/异常路径经由 complete() 在请求线程 end 收 scope，见 8.4）|
| C9 | BLOCK/REPLACE 早退路径 | 请求线程 | 未达 closeRootScope；complete() 在**请求线程**→finish→end()→closeScopeSafely（同线程）→关 scope | ✓ 无泄漏（依赖 complete() 必须在请求线程执行，当前代码成立）|
| C10 | 同步段抛异常 | 请求线程 | chatWithToolcall catch→complete()→同上 | ✓ 无泄漏 |
| C11 | schedule 抛异常降级 | 构造线程 | timeoutGuard=null；仅剩 complete/onTimeout/onError 兜底 | ⚠️ C5 组合下泄漏（M-3）|
| C12 | 超时后业务重试循环 | 业务线程 | 重试喂错（token 浪费 R5）；complete()→finish CAS 失败→no-op | ⚠️ R5 已覆盖 send 吞错；裸 send 噪声（L-2）|
| C13 | 快照恢复（resumeFromSnapshot） | subtaskExecutor | 复用旧 emitter？新 emitter？见 8.4 | ⚠️ 见 8.4 |

### 8.4 快照恢复路径（C13）的遗留风险

`resumeFromSnapshot(snapshot, emitter)` 的 `emitter` 由调用方传入：若**复用旧请求的 emitter**（已 complete 或已 timeout），wrapper 的 `finish()` CAS 已置位 → 快照恢复的后续 `complete()` 只 super.complete()（no-op），span 已在旧请求被 finish 结束 → 恢复段的 round/plan/subagent span 挂在已 stop 的根下（T5 允许挂靠但根已导出）。当前 `ChatContext.ctx=null` 时 `rootSpan` 兜底用 `snapshot.getRootSpan()`（旧根），**新请求应新建 session 根而非复用旧根**——文档 C13 未覆盖，建议：快照恢复走新 Controller 请求（新 session 根）时，旧根在旧请求已收敛，无问题；若恢复走复用 emitter 路径，需显式说明（当前代码无该调用方，`resumeFromSnapshot` 仅在 TaskSnapshot javadoc 被引用，疑似死路径，需核实）。

### 8.5 结论

设计骨架正确（不依赖 onCompletion、双 CAS 幂等、guard 兜底、属主线程 scope 处理），主要问题集中在**异常安全与语义一致性**：N1（finish 异常安全）、N2（R10 承重）阻断；M-1/M-2（guard 时钟与校验）、M-3（降级保证缺口）、M-4（断开语义）、M-5（closeRootScope 幂等）应随实施同步；§3.1 的 super.complete() 抛异常表述需事实修正（V2）。
