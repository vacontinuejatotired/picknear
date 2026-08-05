# TraceId 断链排查流程（思考流转实录）

> **问题**：一次 AI 流程产生的所有 span 各占一个 traceId，Langfuse 树串不起来
> **时间**：2026-08-03（当日 22:00 → 23:50，约 2 小时）
> **结论**：双层次根因，均已修复验证（详见 [交接文档](./TraceId断链排查交接.md)）

---

## 一、排查前的认知起点

交接文档给出了明确的矛盾表述：

> "栈顶正确 + 机制正确 + 手动路径正常，唯独 `agentTracer.start()` 路径的 span 在 OTel 层 parentSpanId=全零。"

并留下三个未闭环环节：A（handler 链诊断）、B（TracingContext 存在性）、C（兜底：显式传父）。

**但我很快发现交接文档本身有三个硬伤**，这决定了整个排查的走向：

1. **A 项代码"已写好未跑"——实际编译失败**：`getObservationHandlers()` 在 micrometer 1.14.5 是 package-private，第一次执行就编译不过。意味着交接者标注的"实测结论"（registry hash、probe 实验、[TRACE-DIAG] 输出）**全部存疑**——测试从没成功跑过。
2. **probe 实验证明不了什么**：手动 `createNotStarted` 后只打印 `getParentObservation()`（observation 层父级）就收手了——它从未 start，没导出过 span。**observation 层父子 ≠ OTel 层父子**，两个层次被交接文档混为一谈。
3. **"栈顶正确"是生产日志的间接观察**，不是受控实验。

所以第一步不是接着 A/B 跑，而是**把测试真正跑起来**，拿到第一手实锤。

---

## 二、第一轮实证：测试跑通后的完整现场

修好编译（A 项改反射）后跑测试，得到决定性现场：

```
[TRACE-DIAG] start=agent.prompt_hook  栈顶=agent.session
[TRACE-DIAG]   create后observation父=agent.session   ← observation 层父级全对！
...
[诊断] span: agent.prompt_hook parent=00000000       ← OTel 层全孤儿！
[诊断] span: agent.probe_test  parent=00000000       ← 【probe 也断链！】手动路径并不正常！
```

**三大实锤**：
- **observation 层父子 100% 正确**（每个 span 创建时父级都捕获到了）
- **OTel 层 100% 孤儿**（parentSpanId 全零），**包括 probe**——交接文档的核心矛盾不成立
- session 的 TracingContext 存在且有 span（B 项通过），handler 链上 tracing handler 在 composite 里（A 项通过）

矛盾从"手动正常 vs 自动断链"收敛为：**桥接环节（observation 层 → OTel 层）在 onStart 时父级丢失**。

## 三、字节码定案：getParentSpan 的"情况 A 短路"

反汇编 micrometer-tracing 1.4.4 的 `TracingObservationHandler.getParentSpan`，发现了问题的钥匙：

```
tc = context.get(TracingContext.class)        ← 第一步查【自己】的 context
if (tc != null) return tc.getSpan();          ← 情况A：自己有 TracingContext → 返回自己的 span，父级忽略！
```

再反汇编 `getTracingContext`：

```
context.computeIfAbsent(TracingContext.class, () -> new TracingContext())  ← 没有就塞一个【空的】进去
```

**链条瞬间闭合**：

```
createNotStarted → openScope → start
                      │            └─ onStart → getParentSpan()
                      │                   └─ context.get(TracingContext) → 非 null（openScope 塞的）
                      │                        → 情况A：返回自己的空 span = null → 新 traceId
                      └─ onScopeOpened → getTracingContext
                             └─ computeIfAbsent 把空 TracingContext 提前塞进 context
```

**openScope 先于 start → onScopeOpened 先于 onStart → 空 TracingContext 提前占位 → onStart 短路父级。**

这一条解释了一切现象：
- 为什么 observation 层父级对（createNotStarted 阶段捕获，早于一切）
- 为什么 OTel 层全孤儿（onStart 阶段被短路）
- 为什么 session "正常"（它是根，断了也看不出来）
- 为什么 probe 手动路径一样断（它 start 了就走同一条路）

**修复**：`start()` 与 `openScope()` 对调——官方 `Observation.start()` 封装就是"先 start 后 openScope"。

## 四、第一轮修复验证：基本全绿，露出第二个断链点

对调后重跑：**agent.* 全部挂上**，但：

```
chat qwen-plus parent=00000000   ← phase1 的流式 chat 还是孤儿
chat qwen-plus parent=...        ← 同步 call() 的三个 chat 全挂上了
```

**同步挂、流式断**——两个断链机制。同时发现测试断言 `decision → session` 失败：decision 实际正确挂在 phase1 下（它在 phase1 的 scope 内创建），**原断言写错了**（全断链时代这条断言从未被真正检验过）。修正断言为 phase1。

## 五、第二层深挖：流式 chat 的 Reactor context 之谜

反汇编 `DashScopeChatModel.internalStream`（model 层）与 `DefaultStreamResponseSpec`（ChatClient 层）：

```
// ChatClient 层（DefaultStreamResponseSpec）
deferContextual: 创建 ChatClient 层观察对象（spring.ai.chat.client）
                 getOrDefault("micrometer.observation", null) → parentObservation → start
...nextStream → model 层（internalStream 同样模式）
contextWrite: 把自己的观察对象 put 进 context
```

**双层嵌套**：ChatClient 层观察对象会覆盖 context 里的值，model 层再从 context 读父。

### 排查实验记录（排除法）

| 实验 | 结果 | 结论 |
|------|------|------|
| ① 最小化 Reactor 传播（单层 contextWrite → deferContextual） | phase1 读到了 | Reactor 机制本身正常 |
| ② 三层嵌套（模拟 ChatClient 中间层，闭包未赋值） | 中层 contextWrite 执行时闭包值 = null；put(null) 抛 NPE | **订阅从下游向上执行，上游闭包变量尚未赋值**；Reactor 禁止 null |
| ③ spring.ai 生产没 NPE | chat 调用正常 | ChatClient 层观察对象**创建成功**（闭包非 null）→ put 的是它自己 |
| ④ 白名单检查 | `spring.ai.chat.client` 在放行前缀内 | 不是 Noop 问题 |

**收网**：model 层从 context 读到的父是 **ChatClient 层观察对象**（有名字、有 observation，但 **handler 链上 meter 优先的 `TracingAwareMeterObservationHandler` 匹配了它 → 不产生 tracing span → context 里没有 TracingContext**）→ model 层 `getParentSpan`：父存在但无 TracingContext → null → 新 traceId。

### 修复：绕开 ChatClient 层

手动 contextWrite 永远救不了——ChatClient 层的 contextWrite 在更上游，会覆盖我们的值。**改在源头绕开**：phase1 流式直接调 `DashScopeChatModel.stream()`（model 层），此时 context 里只有我们写入的 phase1，model 层观察对象直接挂上。model 层自身的 contextWrite 在观察对象创建**之后**执行（lambda 内部链），不污染父级读取。

## 六、最终验证闭环

```
agent.session (根)
├─ agent.prompt_hook   ✓  ├─ agent.phase1   ✓  └─ agent.round.1   ✓
│                          │  ├─ decision ✓    │  ├─ plan ✓（chat ✓）
│                          │  └─ chat ✓✓✓     │  └─ subagent ✓（tool_call ✓ / guard ✓ / chat ×2 ✓）
```

Tests run: 1, Failures: 0, Errors: 0。**全部 span 同 traceId，整棵树串起。**

---

## 七、事后感

### 1. 交接文档的"结论"要当线索，不当事实

这次最大的教训：交接文档把"未验证的推理"写成了"字节码级实测结论"，还构建了一个错误的"核心矛盾"（手动路径正常 vs 自动断链）。**而真相是手动路径一样断**——因为 probe 实验从未 start。排查的前 40 分钟我一度被这个伪矛盾带着绕。**后来证明：只要测试真正跑起来，一切自明。**

### 2. 分层思维的胜利：observation 层 ≠ OTel 层

断链发生在三层之间：observation 层（业务可见）、TracingContext 层（桥接）、OTel span 层（导出）。第一轮实测把"observation 层全对 + OTel 层全断"钉死，问题就被精确锁定在桥接层，避免了在业务代码里大海捞针。

### 3. 字节码是最后仲裁者

Micrometer/Reactor/spring.ai 的运行时行为，文档说的都不如 `javap -c` 准。`getTracingContext` 的 `computeIfAbsent`、`getParentSpan` 的"情况 A 先查自己"、`parentObservation(null)` 无条件清父——每一个都是反汇编才确认的。**排查框架类问题时，字节码是最高证据等级。**

### 4. 时序问题是最阴的 bug

两个根因都是**时序**：`openScope` 先于 `start`（框架生命周期）、`contextWrite` 先于闭包赋值（Reactor 订阅顺序）。这类 bug 的特征是：每个机制单独看都对，组合起来错。**对照官方封装（`Observation.start()` 静态方法）是发现时序错误的捷径。**

### 5. 写测试的成本，远低于猜错的成本

前后跑了 8 轮集成测试，每轮约 30 秒。如果靠生产日志观察，这个问题的每个假设都要在生产环境反复试错，且 Agent 链路带真实 LLM 调用、成本更高。**受控实验 + 诊断打印 + 断言，是唯一让"机制类问题"快速收敛的方式。**

### 6. 遗留的优雅性

第二个修复（绕开 ChatClient 层）是务实选择，不是最优雅方案——ChatClient 层观察对象依旧无 tracing span（meter handler 优先匹配），属于 spring.ai 1.1.2 + Boot 3.4.4 + micrometer 1.14.5 组合的框架级行为，绕开比对抗更稳。若未来升级 spring.ai 版本，可重新评估是否恢复 ChatClient 调用。
