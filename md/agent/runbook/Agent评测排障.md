---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/config/ChatModelObservationConventionConfig.java
  - picknear/src/main/java/com/hmdp/agent/execution/ToolExecutionRecorder.java
  - picknear/src/main/resources/application.yaml
superseded_by:
---

# Agent 评测排障

本文记录 Langfuse 当前配置、验证步骤和已排除问题。评测设计见
[Agent 评测设计](../Agent评测设计文档.md)。

## 1. 当前 Langfuse 资源

以下 ID 对应当前部署，使用前应在 Langfuse 控制台确认仍然存在。

| 资源 | ID | 说明 |
|---|---|---|
| Score Config | `a7a52557-b4c3-4770-a21a-d4ecf4ad9a47` | `answer_quality`，NUMERIC 0-5 |
| Evaluator | `cmtfqj1yx02txad0jv07o25ug` | 回答质量 Judge |
| Evaluation Rule | `cmtfrf4za02zxad0jwzjgnwl5` | 回答质量评估，sampling=1.0 |
| LLM Connection | `cmtfrz9s3032had0facjl7rjy` | DashScope OpenAI adapter |

## 2. 必查配置

### 2.1 主字段补发

代码必须补发：

```text
langfuse.observation.input
langfuse.observation.output
```

不能使用旧 key：

```text
gen_ai.request.content
gen_ai.response.content
```

旧 key 只进入 metadata，Langfuse 主字段仍为空。

### 2.2 evaluator mapping

evaluator 使用主字段：

```text
selectedColumnId=input
selectedColumnId=output
jsonSelector 为空
```

rule 可以省略 variableMapping，继承 evaluator 默认映射。

### 2.3 LLM Connection

确认：

- provider = DashScope OpenAI adapter；
- model = `qwen-turbo`，不带日期；
- `useResponsesApi=false`；
- 正式执行使用 chat completions + tool_choice。

## 3. 验证步骤

1. 发起一次真实 Agent 对话，确保进入 `subagent-exec-chat`。
2. 在 Langfuse 定位对应 trace。
3. 检查 generation 主字段 input/output 非空。
4. 检查工具调用轮 output 是否为 `[调用工具] name(args)`。
5. 在 evaluator 中执行一次测试。
6. 触发 rule 自动执行，不能只看 MCP 预览结果。
7. 查询 `answer_quality` score 是否落库。

## 4. 常见问题

### input/output 恒为 null

检查：

1. `hmdp.ai-observability.chat-observation.include-content` 是否允许补发；
2. 代码是否仍写旧 `gen_ai.request/response.content`；
3. 后端是否真的执行了 `ChatContentSerializer` 补发；
4. 新 trace 是否在修复部署之后产生。

### metadata jsonSelector 取不到值

已知事实：当前 Langfuse 云上 observation evaluator 的 metadata 路径取数不可用。

不要再尝试 `metadata.*`、`$.metadata.*` 等语法。评估数据必须走主字段。

### No object generated / response did not match schema

原因通常是 LLM Connection 开启了 Responses API。

处理：

```text
useResponsesApi = false
```

正式 rule/Execute 会使用 tools + tool_choice 强制调用结构化输出工具。

### 模型名带日期后无法评分

使用无日期模型：

```text
qwen-turbo
```

项目配置和 Langfuse LLM Connection 必须一致。

### rule 评估了错误节点

检查 filter：

```text
type=GENERATION
AND name contains subagent-exec-chat
```

过宽 filter 可能评估 planner 或压缩调用，而不是最终回答。

### 429 限流

主链和评测共用外部模型配额。确认代码中的 429 退避仍生效，不要把限流误判为 evaluator 配置问题。

### listScores 权限被拦

优先使用 Langfuse REST 查询 score。更新 evaluator、rule 或 LLM Connection 仍使用 MCP 或控制台。

## 5. 已证伪和禁止重复尝试

| 方案 | 结论 |
|---|---|
| 从 metadata jsonSelector 取 input/output | 已证伪 |
| 用 gen_ai.request/response.content 作为主字段 | 已证伪 |
| 模型名带日期 | 不使用 |
| 用 MCP testEvaluator 代表正式管道 | 不可靠 |
| Responses API 跑 DashScope judge | 会破坏结构化输出 |

## 6. 修改后的完成条件

1. 产生新 trace，不复用旧 observation。
2. 主字段 input/output 有真实内容。
3. 正式 rule 或 Execute 返回结构化 score。
4. score 能在 Langfuse 查询。
5. 修改内容回写到评测设计文档或本排障手册。
