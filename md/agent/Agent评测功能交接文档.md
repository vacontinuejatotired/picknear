# Agent 评测功能 — 交接文档

> **创建**: 2026-09-01
> **用途**: 新开对话处理评测问题时直接阅读本文，避免重复踩坑
> **状态**: **评测链路已打通**（2026-09-02 实测验证）。代码修复（427ed71：content 补发 key 改 `langfuse.observation.input/output`；ad2f35f：TOOL_CALLS 轮 output 序列化）均已部署生效，observation 主字段有值；evaluator v8 / rule 的 mapping 已改回主字段；`testEvaluator` 实测 score=5、取到真实 input/output

---

## 一、当前状态（一句话）

**Langfuse LLM-as-a-judge 链路已可用**（qwen-turbo 能调、模型名无日期、score 概念正确），但**评测一直报 `{{input}}/{{output}}` 为空 → score=0「无回答/乱码」**。

---

## 二、已完成的配置（Langfuse 云端，勿重做）

| 资源 | ID | 说明 |
|------|-----|------|
| Score Config | `a7a52557-b4c3-4770-a21a-d4ecf4ad9a47` | `answer_quality`，NUMERIC 0-5 |
| Evaluator | `cmtfqj1yx02txad0jv07o25ug` | `回答质量 Judge`，LLM_AS_JUDGE，provider=`dashscope`，model=`qwen-turbo`（**无日期**，LLM Connection 里也是无日期） |
| Evaluation Rule | `cmtfrf4za02zxad0jwzjgnwl5` | `回答质量评估`，sampling=0.5，filter=`type=GENERATION AND name contains subagent-exec-chat`，variableMapping 已指向 metadata jsonPath（见下） |

**Rule 的 variableMapping（已设）**：
```json
input  ← source=metadata, jsonPath=metadata.attributes.gen_ai.request.content
output ← source=metadata, jsonPath=metadata.attributes.gen_ai.response.content
```

**项目代码（已推送 feature 分支，15ff868 等）**：
- `EvaluationProperties`：`agent.evaluation.judge-model.*`，默认 model=`qwen-turbo`（无日期）
- `ToolExecutionRecorder`：`tool.{i}.name/status` 回填（评测数据补齐，Phase 2）
- 429 限流 5s 退避（RetryRunner / StreamingChatInvoker）

---

## 三、根因（重要，别绕回去）

### 现象
judge 收到的 `{{input}}` 被替换成**评估器自己的 prompt**（"你是一个专业的问答质量评估专家…"），而非真实的用户问题。

### 根因链
1. 你们的 trace 是 **OTLP 上报 + 自建观测**（AgentTracer/AgentSpanImpl 写 OTLP attributes），**observation 的主 `input`/`output` 字段恒为 null**——真实数据在 `metadata.attributes.gen_ai.request.content` / `gen_ai.response.content`（ChatModelObservationConventionConfig 补发，文档 §209-210）。
2. Langfuse 的 **UI "Test evaluator" / "Execute evaluator" 走的是 evaluator 版本自带的 variableMapping**（创建时 `selectedColumnId: "input"/"output"`，指向主字段→空），**不走 rule 的 variableMapping**（rule 级映射只对规则自动触发生效）。
3. 所以：我更新 rule 的 variableMapping 对 UI 手动测试**无效**；要改的是 **evaluator 本身版本的 variableMapping**（用 `updateEvaluator` 更新 evaluator 的 `variableMapping`，或重创建）。

### 验证过的证据
- `listObservations` 拉 `2da8783035d26d3d`（subagent-exec-chat）完整 metadata：`input/output` 字段 = null，`metadata.attributes.gen_ai.request.content` = 真实用户消息 ✓
- 手动测试输出确认 `{{input}}` = 评估器 prompt 本身（映射没生效）

---

### 深挖结论（2026-09-01 实测，重要：上面"根因链"有误，别绕回去）

**方案 A（改 evaluator 版本 mapping 为 metadata jsonPath）已被实测证伪**：evaluator 已更新到 v7（mapping=metadata 路径，见 §四），`testEvaluator` 对真实 observation 执行，`{{input}}/{{output}}` 仍为空。

**判别实验（铁证）**：对主字段有值的 judge 调用 observation（`c8b5d33d93548d6f`）执行 testEvaluator：
- `selectedColumnId=input`（主字段）→ **取到了**（映射机制正常）
- `selectedColumnId=metadata` + jsonSelector（试了 6 种语法：`metadata.xxx` / `metadata["xxx"]` / `$.metadata["xxx"]` / `$.metadata.xxx` / `attributes.xxx` / `metadata.scope.version` / `metadata` 根）→ **全部取空**

→ **结论：observation 级 evaluator 的 metadata 取数在当前 Langfuse 云上不可用**（或语法未知），主字段取数可用。评测数据必须走**主字段**。

**真根因（代码侧）**：项目 OTLP 上报的 LLM content 一直补发 `gen_ai.request/response.content`，但 **Langfuse 的 OTLP 转译提取器（extractInputAndOutput 瀑布）不识别这个 key**——它只认 `langfuse.observation.input/output`（SDK 协议，Step 1）、`ai.prompt.messages`（Vercel）、gen_ai.* span events、`gen_ai.input/output.messages` 等。所以转译后主字段恒 null、属性只进 metadata，而 evaluator 从 metadata 又取不到 → 死锁。

**修复（已提交 427ed71）**：`ChatModelObservationConventionConfig` 补发 key 改为 `langfuse.observation.input` / `langfuse.observation.output`（提取瀑布 Step 1 最高优先级，值复用 `ChatContentSerializer` 输出）。部署后 observation 主字段即有值。

---

## 四、进展与下一步（2026-09-01 更新）

### 方案 A（改 evaluator mapping）→ **已证伪，不再走**
evaluator 已更新到 v7（mapping=metadata jsonPath），实测取数仍空（见 §三深挖）。**不要**再花时间试 metadata jsonSelector 语法。

### 方案 B（改代码，治本）→ **已提交 427ed71，待部署**
- content 补发 key：`gen_ai.request/response.content` → **`langfuse.observation.input/output`**
- 涉及文件：`config/ChatModelObservationConventionConfig.java`（行为）+ 3 个注释同步（TraceProperties / ChatContentSerializer / TraceBackendCapabilities）
- CI 已绿（feature 分支），**合并 master 后 watchtower 自动部署**

### 部署完成后（已完成 ✅，2026-09-02）：
1. **跑一轮真实对话**产生新 trace ✓（`610df373ca6673b61912d351294e1f62`，"查看长沙天气和我的博客"）
2. `listObservations` 确认新 generation 的 **主字段 `input/output` 有值** ✓（phase1/planner/compress/exec-chat 全部有值，不再依赖 metadata）
3. **evaluator mapping 改回主字段** ✓（v8：`selectedColumnId=input/output`，jsonSelector 空串）
4. **rule mapping 同步改主字段** ✓（`updateEvaluationRule` 省略 variableMapping → 继承 evaluator 默认映射，variableMapping=null）
5. `testEvaluator`（evaluatorId 模式，走 v8）对 `e2c91971bc53a16c`（subagent-exec-chat）执行 → **score=5**，interpolatedPrompt 中 `用户输入`/`AI 回答` 均为真实内容 ✓

**TOOL_CALLS 轮 output（已修复并部署生效 ✅，ad2f35f）**：`subagent-exec-query-weather,...` 等工具调用轮（finish_reason=TOOL_CALLS）的 output=null——`ChatContentSerializer.toResponseContent` 只提取文本，工具调用响应无文本不写 output，且改 key 后 metadata 也不再保留工具调用参数。修复：抽取 `toolCallsText()`（请求/响应两侧复用），TOOL_CALLS 响应序列化为 "[调用工具] name(args)"；单测 5 用例，CI 绿。实测（trace `93e123cc...`，2026-09-02）TOOL_CALLS 节点 output 已为 `[调用工具] queryWeather({"city":"长沙"})` 等。

---

## 五、已排除的坑（不要重复查）

- ❌ 模型名带日期（`qwen-turbo-2025-04-28`）→ 已改无日期 `qwen-turbo`（LLM Connection 同样无日期）
- ❌ rule filter 太宽（评了 root span）→ 已限定 `name contains subagent-exec-chat`
- ❌ 429 限流 → 已加 5s 退避（那是主链路问题，评测走了 DashScope 也会触发，但当前报错不是 429）
- ❌ **metadata jsonSelector 取数（2026-09-01 证伪）**：evaluator/rule 的 variableMapping 指向 metadata（`metadata.attributes.gen_ai.*`）在当前 Langfuse 云上取不到值（6 种语法实测全空，含 `metadata.scope.version` 简单 key；判别实验证明主字段映射正常）。**评测数据只走主字段，不再试 metadata**
- ❌ Langfuse MCP 不稳定 — 大部分查询工具可用；`updateEvaluationRule` 可用；`listScores` 有时被权限拦（改用 REST `curl $LANGFUSE_BASE_URL/api/public/...`，认证 `-u $PUBLIC:$SECRET`，凭据在 `.env`）

---

## 六、验证清单（部署 427ed71 + 云端 mapping 收尾后）

1. **主字段实证**：新跑一轮真实对话（或 LangfuseSmokeTest），`listObservations` 拉新 generation → 主字段 `input`（消息数组 JSON）/ `output`（回答文本）**非 null**；metadata 里不再有 `attributes.gen_ai.request.content`（被提取器剔除进主字段）
2. **云端 mapping 收尾**：evaluator 存 v8 + rule 的 mapping 都改回主字段（`selectedColumnId: input/output`，jsonSelector 空串），见 §四 清单 3-4
3. **judge 实测**：`testEvaluator`（evaluatorId 模式）对真实 subagent-exec-chat observation 执行 → `interpolatedPrompt` 中 `用户输入：<真实请求>` / `AI 回答：<最终回答>`、**score≠0**、reason 引用真实内容
4. **落库确认**：`listScores(name=answer_quality)` 看到评测产生的 score
5. 新跑一轮对话，观察 rule 自动触发（sampling=0.5）的评分

---

## 七、参考

- 设计文档: `md/agent/Agent评测设计文档.md`（v1.4，含 §5.4 数据源映射、§5.5 Phase 1 实证——§5.4/§5.5 的 gen_ai.request.content 取数表述已过时，以本文 §三深挖结论为准）
- 观测文档: `md/agent/observability/Agent全链路观测架构设计.md`（§5.2.1 观测限制、§209-210 content 补发——补发 key 已变更，文档待同步）
- Langfuse 云接入: `md/agent/observability/Langfuse云接入说明.md`
- 相关代码: `config/EvaluationProperties.java`（评测模型配置）、`config/ChatModelObservationConventionConfig.java`（content 补发，2026-09-01 改 langfuse.observation.*）
- 实测证据（2026-09-01）：真实 trace `1a004aeecc910dc635447040855d44ce`（subagent-exec-chat observation `8539d146dc9911ba`，metadata 有 gen_ai.content 但主字段 null）；判别实验用 judge execution trace `6cbbf814a83ee2c34a6fd5e8dc624731` 的 observation `c8b5d33d93548d6f`（主字段有值，metadata 映射取空）
- 外部依据：Langfuse OTLP 提取瀑布（leeroopedia: Langfuse OTel Input Output Extraction——`langfuse.observation.input/output` 为 Step 1 最高优先级）；GitHub issue #13250（OTel 上报的 trace/observation 列表不显示 input/output，详情页正常）

---

*写于 2026-09-01，交接给新对话继续处理评测取数问题。*