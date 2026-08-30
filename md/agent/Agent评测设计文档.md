# Agent 任务完成质量评测设计文档

> **版本**: v1.0（初始版）  
> **创建**: 2026-08-30  
> **状态**: 设计阶段（调研已完成，实施待排期）  
> **相关文档**: [Agent全链路观测架构设计](./observability/Agent全链路观测架构设计.md) · [Langfuse云接入说明](./observability/Langfuse云接入说明.md) · [Langfuse MCP 接入与使用指南](./observability/Langfuse%20MCP%20接入与使用指南.md)

---

## 版本历史

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| v1.0 | 2026-08-30 | 初始版本：成熟方案调研 + 需求拆解 + 评估器设计 + 数据补齐方案 + 实施步骤 |
| v1.1 | 2026-08-30 | 审查修订：§3.3 解耦边界结论；§6.1 重写（正确 API / 回填位置 / ToolExecutionRecorder 设计 / 编号规则）；§5.4 修正（round.{N}、取数实证标注）；§10 补 units 成本 |

---

## 一、背景与目标

### 1.1 痛点

Agent 模块（两阶段规划 + 工具调用 + 多轮编排）上线后，**没有量化的质量评估手段**：

- 每次验证只能"跑个对话干瞪眼看调用情况"——人肉翻日志 / Langfuse trace 判断这次回答得好不好
- 改 prompt、改工具、改路由后，无法回答"质量是变好了还是变差了"
- 没有可追踪的质量基线，迭代回归靠感觉

### 1.2 目标

自动化评估 Agent **任务完成质量**，形成可追踪、可对比、可告警的量化分数体系，支撑：

1. **迭代回归**：改 prompt/工具/路由后，对比前后质量分
2. **质量监控**：质量分/成功率看板，低于阈值人工介入
3. **评估集沉淀**：典型对话入库，回归测试（Dataset/Experiment）

### 1.3 约束（用户拍板）

| 约束 | 说明 |
|------|------|
| **不造轮子** | 优先采用成熟评估体系（Langfuse 平台评估），项目侧不写评估引擎 |
| **放弃用户反馈路线** | 用户量不足，靠用户打标数据太稀疏，不可行 |
| **观测复用** | 评估数据源复用现有 Langfuse trace 埋点（AgentField 注册表），不另起炉灶 |

---

## 二、成熟方案调研结论（2026-08）

### 2.1 Langfuse 平台评估体系（首选）

项目已深度集成 Langfuse 云（观测主后端），其平台自带完整评估体系，2026 年持续增强：

| 能力 | 说明 |
|------|------|
| **LLM-as-a-judge evaluator** | 云端配置评估器：自定义评分 prompt + 绑定 judge 模型，自动对 trace 打分 |
| **Observation 级评估** | 2026-02 起支持评估单个 observation（如某个工具调用、某次 generation），比 trace 级更精细 |
| **分类/布尔/数值分** | 2026-03 支持 categorical scores，不止单一数值 |
| **API 管理评估器** | 2026-04 起可用公共 API 创建/管理 LLM-as-a-judge，无需 UI 手工操作 |
| **触发方式** | 事件驱动（trace 完成后自动跑）/ 定时批量 / 手动；数据源可选 trace 或 dataset |
| **judge 模型配置** | LLM Connections 体系支持自定义 OpenAI-compatible 端点——本项目模型正是 DashScope MaaS compatible-mode，可复用接入 qwen 当 judge（社区有 Qwen 配置讨论）；也可用 Langfuse 默认 judge 模型 |
| **External evaluation pipeline** | 官方承认路线：评估在别处跑（如项目内规则），结果经 SDK 写回平台做展示聚合 |

**已知坑**（社区实践）：judge 模型连接需先建对 LLM Connection；LLM-as-judge 有 LLM 调用成本；评估器配置项多，初次配置有学习成本。

### 2.2 行业评估框架对比

| 框架 | 定位 | Agent 支持 | Java 可用性 | 本项目契合度 |
|------|------|-----------|------------|---------------|
| **Langfuse Evals** | 生产观测内评估 | trace 级 + observation 级 LLM judge | ✅ Java 侧只埋点，评估在云端 | **高（选定）** |
| **Ragas** | RAG/Agent 指标库 | 工具调用准确率/正确性等 agent 指标在扩展 | ❌ Python 为主；spring-ai-ragas（Java 移植）偏 RAG 指标、agent 覆盖有限 | 中（备选参考） |
| **LangSmith** | 观测+评估一体 | trajectory evaluation 最强 | ❌ Python/LangGraph 生态 | 低（换平台成本高） |
| **DeepEval / Promptfoo** | 测试驱动评测 | 可评估 agent 流程 | ❌ Python/Node | 低（适合 CI 测试集，不适合生产在线评估） |

### 2.3 结论

- 业界 Agent 任务评估普遍 = **确定性规则指标（成本/成功率） + LLM-as-a-judge（主观质量分）**
- 本项目最顺的成熟路线：**Langfuse 平台评估体系**——trace 结构已完整（AgentField 全链路字段），评估器云端配置零 Java 代码，分数原生可视化
- 项目侧职责收敛为两件事：**① 补齐评估输入数据（trace 字段）② 配合触发**，不写评估引擎

---

## 三、评估架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    项目侧（Java，现状 + 补齐）                 │
│                                                             │
│  Agent 请求链路（两阶段 + 多轮编排 + 工具调用）                │
│    ↓  AgentTracer 埋点（AgentField 注册表）                   │
│  Langfuse trace（session → phase1 → plan → round            │
│    → subagent → tool_call → guard 全链路 span）              │
│    + chat-observation.include-content（LLM 输入输出全量）     │
│    + 【补齐】tool.{i}.name / tool.{i}.status 逐工具回填       │
└────────────────────────────────┬────────────────────────────┘
                                 │ OTLP 上报
┌────────────────────────────────▼────────────────────────────┐
│              Langfuse 云端（评估主体，零项目代码）             │
│                                                             │
│  Evaluator ① 回答质量 judge（LLM-as-a-judge）                │
│  Evaluator ② 执行质量 evaluator（规则/LLM）                  │
│  Evaluator ③ 规划质量 judge（二期，可选）                    │
│    ↓ 触发：事件驱动 / 定时批量 / 手动                        │
│  Score 写回（trace 内联 + 看板聚合）                          │
│  Dataset / Experiment（评估集沉淀 + 回归对比）                │
└─────────────────────────────────────────────────────────────┘
```

**职责边界**：评估主体在平台侧（Langfuse 云端配置，零 Java 评估代码）；项目侧只做"数据补齐 + 触发配合"。

### 3.3 评测与观测的解耦边界（2026-08-30 拍板）

**评测不需要单独解耦**，理由：

1. **没有"引擎"可解耦**：观测的解耦（TraceBackend 多实现）解的是写入侧——埋点可能写给多个后端，所以需要接口。评测的评估执行在 Langfuse 云端（evaluator 配置），项目侧没有评估逻辑跑在 Java 里，抽象 EvaluationBackend 没有第二个实现可切
2. **数据通路已被观测的解耦覆盖**：评估器输入就是 trace——trace 能进哪个后端（`hmdp.ai-observability.backend.type` 一行切换），评估器就自然跟到哪个后端，评测搭观测解耦的便车
3. **只保留两个薄点**：
   - trace 字段平台无关：`tool.{i}.name/status` 是通用 OTLP 属性，不绑 Langfuse
   - 若 Phase 1 验证发现平台 CODE evaluator 覆盖不了复杂规则、需走 External pipeline（项目内跑规则写回分数），届时加一个薄门面 `EvaluationReporter`（类比 `HistoryRecorder`，内部按 reporter.type 分发 Langfuse REST / Console / Noop）——触发条件满足才做，不预建

**评估引擎真正解耦的触发条件**：出现"项目内执行评估"的硬需求（平台 evaluator 无法满足），届时按项目既有模式（`ToolGuardPolicy + Manager` 策略聚合、`PlanRouter` DI 选择）再解耦，YAGNI。

---

## 四、评估对象与维度

### 4.1 评估对象（三层）

| 层级 | 对象 | 示例 |
|------|------|------|
| **会话级** | 一次完整对话的最终产出（回答 + 意图达成度） | "查长沙天气并与上海对比" → 最终回答质量 |
| **过程级** | 任务执行链（规划 → 工具调用序列 → 结果） | 工具选择、执行成功率、参数正确性、冗余调用 |
| **系统级** | 链路行为指标 | 轮数、token、延迟、重试、守卫拦截/审批事件 |

### 4.2 评估维度（四组）

| 维度 | 子项 | 评价问题 | 判定方式 |
|------|------|---------|---------|
| **回答质量** | 相关性 / 完整性 / 准确性 / 格式 | 答非所问？信息缺漏？事实错误？结构清晰？ | LLM judge（主观） |
| **规划质量** | 意图理解 / 工具选择 / 计划覆盖 | 理解了意图？选了正确工具？该查的都查了？ | LLM judge（主观） |
| **执行质量** | 成功率 / 参数正确性 / 冗余 / 越权 | 工具调成了？参数对？重复调用？被守卫拦了？ | 规则为主（客观） |
| **效率成本** | 轮数 / token / 延迟 | 几轮收敛？token 是否浪费？ | 规则（客观） |

### 4.3 评分口径建议

- 每个 judge 输出 **0-5 分**（Langfuse score 天然支持 1-5/0-1 数值）+ **一句话理由**（注释）
- 综合分 = 各维度加权（回答 0.4 / 执行 0.3 / 规划 0.2 / 效率 0.1，权重待实测调整）
- 阈值建议：≥4.0 良好、3.0-4.0 及格、<3.0 需介入（初期仅记录不告警）

---

## 五、评估器设计（核心）

### 5.1 评估器清单

| # | 评估器 | 类型 | 评估对象 | 输入 | 输出 | 阶段 |
|---|--------|------|---------|------|------|------|
| ① | **回答质量 judge** | LLM-as-a-judge | trace（会话级） | 用户输入 + 工具链摘要 + 最终回答 | 0-5 分（相关/完整/准确/格式 4 子项）+ 理由 | Phase 3 |
| ② | **执行质量 evaluator** | 规则型（Langfuse 平台规则 evaluator，或项目内算后写回） | trace（过程级） | 工具调用序列（名称/状态）、round 数、守卫事件 | 0-5 分（成功率/冗余/越权）+ 明细 | Phase 3 |
| ③ | **规划质量 judge** | LLM-as-a-judge（二期可选） | trace（过程级） | 用户输入 + plan.tools + 执行结果 | 0-5 分（意图理解/选工具/覆盖）+ 理由 | Phase 5 |

### 5.2 judge prompt 设计要点（评估器 ①）

```
角色：你是资深 QA 评审，评估一次 AI 助手任务完成质量。先通读，再打分，最后给理由。

输入：
- 用户请求：<用户原始输入 originalContent>
- 工具调用链：<tool.{i}.name/status 摘要 + 各工具结果摘要>
- 最终回答：<assistant 最终文本>

评分标准（每项 0-5）：
- 相关性：回答是否针对用户请求，不答非所问
- 完整性：该查的信息是否都查了（对照工具链），关键信息是否遗漏
- 准确性：回答中的事实/数字是否与工具结果一致，有无幻觉
- 格式：结构是否清晰（分点/表格/总结）

输出（严格 JSON，禁止其他文本）：
{"score": <综合0-5>, "sub_scores": {"relevance": x, "completeness": x, "accuracy": x, "format": x}, "reason": "<一句话理由>"}
```

设计要点：
- **防偏置**：prompt 不透露 judge 模型身份；要求"先通读 → 打分 → 理由"固定流程
- **强制 JSON**：输出结构固定，便于 Langfuse 解析与告警
- **锚定示例**：评分标准中给出 5 分与 2 分的对照行为描述（后续 Dataset 沉淀后引入 few-shot）
- **数据脱敏**：judge 输入来自 trace 属性（AgentField 已按 SUMMARY/DIAGNOSTIC 脱敏），理由中不含用户隐私

### 5.3 judge 模型选择

| 方案 | 说明 | 风险 | 决策 |
|------|------|------|------|
| A. Langfuse 默认 judge 模型 | 零配置，平台托管 | LLM 调用成本按量计费；模型与主链路异构（更客观） | 先跑通验证 |
| B. 自定义 LLM Connection 连 DashScope qwen | 复用现有 API Key + OpenAI-compatible 端点 | **qwen 评 qwen 同质偏置**（对自身生成过于宽容）；配置有门槛 | 实测对比后定 |

> 决策：**Phase 1 先用 A 验证全链路**（成本/分数/展示），Phase 3 上线前用 B 跑同批 trace 对比两者分布，差异大再议。

### 5.4 数据源映射表（评估输入 ← trace 字段）

以 `AgentField` 注册表为准（`observability/model/AgentField.java`）：

| 评估需要 | trace 字段 | span | 现状 |
|---------|-----------|------|------|
| 会话标识 | `conversation.id` / `user.id` | agent.session | ✅ 已有 |
| 用户原始输入 | `gen_ai.request.content`（phase1 generation） | chat <model> | ✅ include-content 已开 |
| 最终回答 | `gen_ai.response.content` | chat <model> | ⚠️ **取数位置待实证（见下方 B-2）** |
| 工具清单 | `plan.tools` | agent.plan | ✅ 已有 |
| 工具数量/轮次 | `tool_count`；轮次由 `agent.round` span 的 **semantic**（轮次号）区分（非属性 key） | agent.round | ✅ 已有 |
| **逐工具名称/状态** | **`tool.{i}.name` / `tool.{i}.status`** | agent.subagent | ⚠️ 本次实现（§6.1） |
| 守卫/审批事件 | `guard.decision` / `hook.decision` | agent.guard | ✅ 已有 |
| 规划来源/校验 | `validate_result` | agent.plan | ✅ 已有 |
| 阶段/状态 | `status` | 各 span | ✅ 已有 |

**实证标注（审查 B-2/B-3/B-7，Phase 1 必验）**：

- **B-2 最终回答取数**：一条 trace 有 5 类 LLM span（phase1-chat / planner-chat / subagent-exec-chat / subagent-compress-chat / llm-reason-chat），SSE 流式 phase1 的模型层 span 在 Reactor 线程创建、命名受限（观测架构 §5.2.1）；"用户最终看到的回答"取哪个 span 需 Phase 1 用真实 trace 实证。另：默认子 Agent 路径的工具结果（压缩摘要）只进 prompt 历史/`doneSummary`，**不在 span 属性**（`tool.result_summary` 仅回退路径的 `agent.tool_call` 有）——judge 评"完整性/准确性"是否再补 `tool.{i}.result` 需拍板（暂不补，先看 name/status 够不够）
- **B-3 回退路径分流**：`tool.{i}` 只在 subagent span；回退路径（`feature.subagent.enabled=false`）只有 `agent.tool_call` 的 `status`/`tool.result_summary`。执行质量 evaluator 需按 span 类型分流取数（subagent 读 `tool.{i}`，回退路径读 `agent.tool_call`）
- **B-7 采样率对齐**：观测日常采样率 0.1（验证期 1.0）——"每日评估新增 trace"实际评估的是**已采样的 trace**。评估起步期建议评估窗口内临时提采样率，或明确评估对象 = 已采样 trace；临时提采样对 50k units 预算的影响在 Phase 1 实测

---

## 六、数据补齐方案（项目侧唯一 Java 改动点）

### 6.1 AgentField `tool.{i}.name/status` 回填（Phase 2，v1.1 定稿）

#### 现状

`AgentField.TOOL_ENTRY_NAME` / `TOOL_ENTRY_STATUS` 已在注册表（M4 规划时注册模板），但无任何调用点回填。

#### 回填位置（审查 A-2 定稿）：模板方法公共钩子，三策略统一走

逐工具执行在三个策略（`SerialStrategy` / `ParallelStrategy` / `DagStrategy`）的 `executeRound` 钩子内，`ToolExecutionFacade`/`AbstractToolLoop` 主循环都拿不到每工具状态。解法：**AbstractToolLoop（模板方法）新增统一工具执行点 `invokeToolAndRecord`**，三策略把 `cb.call(...)` 包一层即可，公共逻辑收敛模板、零重复：

```java
// AbstractToolLoop 新增（protected，三策略共用；泛型免 cast）
protected <T> T invokeToolAndRecord(String toolName, Supplier<T> invoker) {
    try {
        T result = invoker.get();
        recorder.record(toolName, SubTaskStatus.COMPLETED);
        return result;
    } catch (ConfirmRequiredException e) {
        throw e;                              // CONFIRM：执行未发生，不记录
    } catch (Exception e) {
        recorder.record(toolName, SubTaskStatus.FAILED);
        throw e;
    }
}
```

三策略接入点：
- `SerialStrategy`：`cb.call(tc.arguments(), toolCtx)` → `invokeToolAndRecord(tc.name(), () -> cb.call(...))`
- `ParallelStrategy`（`callPhase`）：同上
- `DagStrategy`（`buildToolInvokers` 的 `ToolInvoker.invoke()`）：同上——DefaultPlanExecutor 重试时 invoke 多次，状态终态覆盖，天然符合编号规则

> 状态复用 `plan/model/SubTaskStatus.java` 终态（COMPLETED/FAILED）——五态两用约定：活链只写终态，不写 READY/RUNNING。

#### ToolExecutionRecorder（新类，`execution/ToolExecutionRecorder.java`）

线程安全收集 + 轮末统一刷入（规避策略内并发写 span 属性）：

```java
@Component
public class ToolExecutionRecorder {
    // 序号与占号（并发安全）
    private final AtomicInteger nextIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Integer> nameToIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> nameToStatus = new ConcurrentHashMap<>();
    private volatile AgentSpan span;   // 当前 subagent span（execute 入口绑定）

    /** execute 入口调用：绑定 span + 清空收集（subagent span 内全局编号） */
    public void reset(AgentSpan span) { this.span = span; ...清空...; }

    /** 工具执行终态（并发线程调用安全；同工具重复调用不占新号，状态覆盖） */
    public void record(String toolName, SubTaskStatus status) {
        nameToIndex.putIfAbsent(toolName, nextIndex.getAndIncrement());
        nameToStatus.put(toolName, status.name());
    }

    /** 轮末/出口调用：把收集结果写入 span 属性（主线程；span 为 null 或异常静默，Fail-Open） */
    public void flush() {
        if (span == null) return;
        nameToIndex.forEach((name, i) -> {
            span.set(AgentField.TOOL_ENTRY_NAME, String.valueOf(i), name);
            span.set(AgentField.TOOL_ENTRY_STATUS, String.valueOf(i), nameToStatus.get(name));
        });
    }
}
```

**正确 API（审查 A-1 定稿）**：`AgentSpan.set(AgentField field, String segment, String value)` 三参重载——第一参数是 `AgentField` 枚举、段值经 `String.valueOf(i)` 传入。~~`key(int)`~~ 编译不过（`key(String...)`），~~`key()` 结果当枚举~~ 语义倒挂，均不可用。

**编号规则（审查 B-1 定稿）**：`tool.{i}` 序号 = **subagent span 内全局单调递增**（一次 `execute()` 内首个工具 = 0，`reset` 时归零）；同工具重复调用/重试**不占新号**（`putIfAbsent` 占号 + 状态覆盖）；CONFIRM（审批挂起）不记录（执行未发生，安全事件由 `guard.decision` 承载）。

**flush 时机**：`AbstractToolLoop.execute` 主循环内每轮 `executeRound` 返回后 `finally` 调用（异常冒泡路径也保证写入），`execute` 出口兜底一次 + `reset(null)` 解绑防串用。flush 幂等全量写（每次执行工具数 ≤ `maxTotalCalls=10`，量小；set 同 key 覆盖无副作用）。

**span 传递链**：`RoundExecutionProxy`（创建 subagent span）→ `ExecutionSession.subagentSpan`（+字段）→ `ToolExecutionFacade` → `RetryRunner.executeWithRetry(+参数)` → `ToolLoopContext.subagentSpan`（+字段）→ `AbstractToolLoop.execute(ctx)` 入口 `recorder.reset(ctx.subagentSpan())`。

#### 验证（审查 A-1 建议）

- 单测（InMemorySpanExporter 断言属性出现）：成功路径 `tool.0.name=toolA` / `tool.0.status=COMPLETED`；失败路径 `FAILED`；重复调用不占新号（同工具两次 → 只有 `tool.0` 无 `tool.1`）
- Langfuse 端：listObservations 确认 `tool.{i}` 字段出现在 agent.subagent span

#### 原则

只补 trace、不落库自建表（评估在平台侧读 trace，不造轮子）；如后续有查询/报表硬需求再加表。

### 6.2 观测配置确认（无改动，仅核对）

- `hmdp.ai-observability.chat-observation.include-content: true` 已开 → phase1/subagent 的 LLM 输入输出全量进 trace，judge 可读上下文 ✅
- `trace-filter.include-prefixes` 已含 `agent.` / `spring.ai.` / `gen_ai.` → 评估相关 span 不会被白名单过滤 ✅

---

## 七、触发与调度

| 触发方式 | 说明 | 成本 | 建议 |
|---------|------|------|------|
| **事件驱动** | trace 完成后自动跑评估器（Langfuse 平台配置） | 每条对话 1 次 judge LLM 调用，成本随量走 | 二期开放 |
| **定时批量** | 平台定时任务/外部调度，按时间范围批量评估新增 trace | 可控（可限条数/采样率） | **起步采用** |
| **手动** | 对单条 trace / dataset 条目手动触发 | 按需 | 调试/回归随时可用 |

> 起步建议：**定时批量（如每日评估当日新增，采样率 100%）+ 手动**；评估器稳定、成本可接受后再开事件驱动。

---

## 八、结果呈现与使用

### 8.1 Langfuse 原生展示（零自建 UI）

- **Score 内联**：评估分数直接显示在 trace 详情页，一条对话"质量一目了然"
- **看板聚合**：按维度（回答/执行/规划）、按时间趋势、按用户聚合——"质量有没有变差"看板回答

### 8.2 Dataset / Experiment 回归流程

1. 沉淀评估集：把典型对话（好/坏样本各 20+ 条）入 Langfuse Dataset
2. 回归触发：改 prompt / 工具 / 路由后，对 Dataset 批量跑 evaluator → Experiment
3. 对比：Experiment 间平均分 diff，判断迭代是正优化还是回归

### 8.3 告警（后期）

- 规则：当日平均分 < 阈值 或 工具成功率 < 阈值 → 日志/通知
- 初期只记录不告警，基线稳定后再加

---

## 九、实施步骤（Phase 划分）

| Phase | 内容 | 产出 | 预估 |
|-------|------|------|------|
| **Phase 1 平台验证** | Langfuse 云端手工建 1 个回答质量 judge evaluator，对现有 trace 跑通 | 全链路验证（模型可用/成本/分数展示） | 0.5-1d |
| **Phase 2 数据补齐** | AgentField `tool.{i}` 回填（Java + 单测） | 工具明细进 trace | 0.5d |
| **Phase 3 评估器上线** | 配置回答质量 judge + 执行质量规则 evaluator，定时批量触发 | 生产可评估 | 1d |
| **Phase 4 评估集与回归** | Dataset 沉淀 + Experiment 回归流程跑通 | 迭代回归闭环 | 1d |
| **Phase 5 扩展（可选）** | 规划质量 judge / 项目内规则写回 score / 事件驱动 / 告警 | 完善 | 按需 |

> Phase 1 是**前置验证**：先确认 Langfuse 云端 judge 能跑、成本可接受，再投入 Java 改动。

---

## 十、风险与开放问题

| 风险 | 影响 | 缓解 |
|------|------|------|
| **qwen 评 qwen 同质偏置** | judge 对自身生成过宽容，分数虚高 | Phase 1 用 Langfuse 默认模型验证；上线前 A/B 对比分布 |
| **LLM-as-judge 成本** | 每条对话 1+ 次额外 LLM 调用 | 定时批量 + 采样；规则 evaluator 零成本兜底 |
| **免费档配额（含评估 units）** | Langfuse 免费档 50k units/月摄取配额。**评估新增两类 units 消耗：score 写入（每个 score 计 1 unit）+ 平台内 evaluator 产生的 judge LLM observation（每条计 units）**——与观测 trace 消耗同池 | Phase 1 实测"评估 1 条 trace 新增多少 units"，连同观测日常消耗一起核算预算，回写本节（审查 B-5） |
| **评估器配置复杂度** | 首次配置门槛高、易踩坑 | 参考社区实践文章；Phase 1 专门排期验证 |
| **数据缺口影响评估** | tool.{i} 未回填前，过程级评估不完整 | Phase 2 补齐后才开过程级评估 |

### 待拍板决策点（实测后定）

| # | 决策点 | 选项 | 触发时机 |
|---|--------|------|---------|
| D1 | 评估对象范围 | 只评最终回答 / 回答 + 过程链 | Phase 2 数据补齐后 |
| D2 | judge 模型 | Langfuse 默认 / DashScope qwen（LLM Connection） | Phase 1 验证后 |
| D3 | 触发方式 | 定时批量 / 事件驱动 | 成本实测后 |
| D4 | 维度粒度 | 综合 judge ×1 / 分维度 judge ×2-3 | Phase 1 跑通后 |

---

## 十一、参考文档

- 现有项目文档：[Agent全链路观测架构设计](./observability/Agent全链路观测架构设计.md)（span 串树与字段规范）· [Langfuse云接入说明](./observability/Langfuse云接入说明.md)（M1 链路）· [Langfuse MCP 接入与使用指南](./observability/Langfuse%20MCP%20接入与使用指南.md)（平台工具与配额）
- Langfuse 官方：[LLM-as-a-Judge Evaluation](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge) · [LLM Connections](https://langfuse.com/docs/administration/llm-connection) · [External Evaluation Pipelines](https://langfuse.com/docs/evaluation) · Changelog（observation-level evals 2026-02 / categorical scores 2026-03 / judge API 2026-04）
- 社区实践：[在 Langfuse 上配 LLM-as-Judge，五个真坑](https://cloud.tencent.com.cn/developer/article/2700080) · [Qwen judge 模型配置讨论（Langfuse GitHub #10798）](https://github.com/orgs/langfuse/discussions/10798)

---

## 十二、设计评审意见（2026-08-30）

> 评审依据：对照代码现状（`observability/model/AgentField.java`、`observability/api/AgentSpan.java`、`execution/ToolExecutionFacade.java`、`execution/RetryRunner.java`、`execution/loop/strategy/SerialStrategy.java`、`subagent/loop/AbstractToolLoop.java`）与《Agent全链路观测架构设计》《Langfuse云接入说明》核查。整体结论：**方案方向与选型成立（Langfuse 平台评估 + 项目侧只补数据），可进入实施；但 Phase 2 的 Java 改动文档有两处硬伤（A-1/A-2），B 组建议在 Phase 1 用真实 trace 实证后回写**。

### A. 必须修订（影响实施正确性）

**A-1 6.1 示例代码 API 误用，照抄无法编译**

文档示例：

```java
agentSpan.set(AgentField.TOOL_ENTRY_NAME.key(i), toolName);
agentSpan.set(AgentField.TOOL_ENTRY_STATUS.key(i), status);
```

问题：

1. `AgentField.key(...)` 签名是 `key(String... segments)`（AgentField.java:113），`key(i)` 传 int 编译报错，需 `String.valueOf(i)`。
2. 更根本的：`AgentSpan.set(AgentField field, String value)`（AgentSpan.java:34）第一参数类型是 **`AgentField` 枚举**，不是 String。`key()` 返回的是字符串 key（`tool.0.name`），把它当第一参数传给 `set`，编译层面匹配不上；若误用 `attribute(String,String)` 逃生口则 key/value 倒挂、语义全错。

正确写法应走 AgentSpan 的参数化**三参重载**（AgentSpan.java:45，`set(AgentField, String segment, String value)`，段值 + 值分开传）：

```java
agentSpan.set(AgentField.TOOL_ENTRY_NAME, String.valueOf(i), toolName);
agentSpan.set(AgentField.TOOL_ENTRY_STATUS, String.valueOf(i), status);
```

建议：Phase 2 开工前先按正确 API 更新示例，并补一条最小单测（InMemorySpanExporter 断言 `tool.0.name`/`tool.0.status` 属性出现），避免照文档实现时踩编译坑。

**A-2 6.1 回填位置描述不准确**

文档建议回填在"`ToolExecutionFacade.java` 或 `AbstractToolLoop.java` 循环内"。

核实结果：

- `ToolExecutionFacade.execute` 只做"组装上下文 → 委托 `RetryRunner` → `toolLoop.execute(ctx)`"（ToolExecutionFacade.java:57-127），**内部没有逐工具循环，拿不到每个工具的执行状态**（它只有 plan 里计划中的 distinct toolNames）。
- `AbstractToolLoop.execute` 是主循环（AbstractToolLoop.java:72-102），但逐工具执行在抽象钩子 `executeRound` 的**三个策略实现**里：`SerialStrategy`（SerialStrategy.java:49-77，`cb.call` 处）/ `ParallelStrategy` / `DagStrategy`。成功/失败/重复/工具不可用四态都在 `executeRound` 的 try/catch 分支判定。
- 若按文档位置（AbstractToolLoop 主循环）回填，只能做到"轮粒度"，多工具轮会互相覆盖 `tool.{i}`，且拿不到失败/重复状态。

建议：回填收敛到统一工具执行点——优先下沉到 `GuardedToolCallback` 统一包装层（唯一的工具调用入口），或在三个策略的 `executeRound` 中 `cb.call(...)` 前后各写（后者需三处重复，注意 DAG 并发的写安全）。失败路径在 catch 分支写 `FAILED`。

### B. 建议补齐（影响评估质量与覆盖）

**B-1 `tool.{i}` 编号规则未定义（跨轮/并发歧义）**

subagent span 内可能多轮（`maxToolRounds`），并发策略下一轮多工具。i 是"全局序号"还是"轮内序号"？重试/重复调用是否占号？编号不定，Langfuse 端同字段被不同含义覆盖，看板聚合失真。建议明确"全局单调递增（subagent span 内首个工具 = 0），重复调用不占新号（status 覆盖）"。

**B-2 "最终回答"取数位置未实证（Phase 1 必验项）**

5.4 将"最终回答"映射到 `gen_ai.response.content`，但一条 trace 有 5 类 LLM span（phase1-chat / planner-chat / subagent-exec-chat / subagent-compress-chat / llm-reason-chat）。SSE 流式 phase1 的模型层 span 在 Reactor 线程创建、命名受限（观测架构文档 §5.2.1 已知限制），且"用户最终看到的回答"与 phase1 流式内容、子 Agent 聚合结论的关系需实测确认。

另一个缺口：subagent 默认路径下工具结果（压缩摘要）进的是 prompt 历史 / `doneSummary`，**不在 span 属性**；`tool.result_summary` 只在回退路径的 `agent.tool_call` span 有。judge 要"对照工具链判断完整性/准确性"，只有 `tool.{i}.name/status`（无结果内容）信息不足，是否再补 `tool.{i}.result` 需拍板。

建议：Phase 1 用一条真实 trace 实证"最终回答取哪个 span / 工具结果能否取到"，在 5.4 表后补一列"Langfuse evaluator 侧实际取数方式（variableMapping 路径）"。

**B-3 执行质量 evaluator 对回退路径缺数据**

`tool.{i}` 只回填 subagent span；回退路径（TaskExecutor / LLM_REASON）无 `tool.{i}`，只有 `agent.tool_call` 的 `status`/`tool.result_summary`。观测架构文档明确要求"业务观测必须覆盖两条路径"（§1.2 提醒）。评估器若只读 subagent 的 `tool.{i}`，回退路径 trace 的过程级评估会缺数据。建议执行质量 evaluator 按 span 类型分流（subagent 读 `tool.{i}`，回退路径读 `agent.tool_call`），并在 5.4 表标注两路径取数差异。

**B-4 状态枚举 RUNNING 与回填时机矛盾**

6.1 示例状态列 `COMPLETED / FAILED / RUNNING`，但回填点定在"每个工具执行完"——执行完的状态不可能是 RUNNING。要么改为"执行前写 RUNNING、执行后覆盖为 COMPLETED/FAILED"（多一次写入），要么去掉 RUNNING。并发策略下多线程同时写同一 subagent span 属性，还需确认 `AgentSpanImpl` 属性写入的线程安全。

**B-5 评估带来的 Langfuse units 成本未估算**

观测口径 1 unit = 1 个 trace/observation/score。评估会新增：score 写入（每个 score 计 units）+ judge 的 LLM observation（若走平台内 LLM Connection evaluator）。风险表只提"LLM-as-judge 调用成本 + 免费档配额"，未把这两项量化进 50k units/月预算。建议 Phase 1 实测"评估 1 条 trace 新增多少 units"，结论回写 §十 风险表。

**B-6 5.4 映射表 `round.{N}` 不是真实字段**

`AgentField` 注册表无 `round.{N}`。轮次通过 `agent.round` span 的 semantic（轮次号）区分（观测架构文档 §5.1），不是属性 key。该行应改为"轮次由 agent.round 的 semantic 区分"，或删去 `round.{N}`。

**B-7 评估覆盖与采样率未对齐**

观测日常采样 0.1（application.yaml:47，当前 1.0 验证期）。"每日评估当日新增 trace"实际评估的是被采样的 10%。文档建议起步"采样率 100%"评估，与日常 0.1 采样的关系需说明：评估期是否临时提采样？临时提采样对 50k units 预算的影响？建议明确"评估对象 = 已采样的 trace"，并评估是否需要"评估窗口内临时 sampling=1.0"。

### C. 可考虑（完善性）

- **C-1 执行质量 evaluator 类型选型可更明确**：Langfuse 平台评估器目前两类：LLM_AS_JUDGE 与 CODE（Python/TypeScript）。5.1 的"规则 evaluator"实际是平台侧写脚本读 trace 属性；"项目内算后写回"与"不造轮子、零 Java 评估代码"约束有张力（属官方 External pipeline 路线，不算违约），建议明确优先平台 CODE evaluator，写回仅作兜底。
- **C-2 judge JSON 输出与 evaluator outputDefinition 对齐**：5.2 的 `{score, sub_scores, reason}` 结构需在 Langfuse evaluator 的 output 解析配置中声明（score 字段映射、sub_scores 是否拆为独立 score）。Phase 1 验证项建议加"JSON 解析正确入库"。
- **C-3 会话级 vs trace 级边界**：当前 SSE 一请求一 session（根 span = 一次请求）。若前端同 conversationId 多轮，"会话级评估"= 多个 trace，建议明确"评估单位 = trace = 单次问答"，避免跨 trace 聚合复杂度。
- **C-4 综合分混算主观/客观**：4.3 综合分 = 回答(judge 0.4) + 执行(规则 0.3) + 规划(judge 0.2) + 效率(规则 0.1)，单一数字可能掩盖"回答好但执行差"。建议展示/告警保留分维度分，综合分仅作总览。

### 审查结论

方案整体成熟，选型与约束（不造轮子 / 弃用户反馈 / 观测复用）自洽，方向可接受。落地前必须先修 **A-1（示例代码 API）、A-2（回填位置）**；B 组建议在 **Phase 1 平台验证**阶段用真实 trace 实证后再定稿，重点 B-2（最终回答/工具结果取数）、B-5（评估成本 units 预算）。Phase 2 的 Java 改动建议开工前先按 A-1 的正确 API 补单测示例。

---

*文档结束*
