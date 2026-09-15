---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/config/properties/EvaluationProperties.java
  - picknear/src/main/java/com/hmdp/agent/config/ChatModelObservationConventionConfig.java
  - picknear/src/main/java/com/hmdp/agent/execution/ToolExecutionRecorder.java
  - picknear/src/main/java/com/hmdp/agent/observability/support/ChatContentSerializer.java
  - picknear/src/main/resources/application.yaml
superseded_by:
---

# Agent 评测设计

本文描述当前评测链路。Langfuse 云端配置、验证步骤和已排除问题见
[Agent 评测排障](runbook/Agent评测排障.md)。

## 1. 当前定位

评测执行在 Langfuse 云端，应用侧负责提供可读取的数据契约，不在 Java 内实现评估引擎。

当前已完成：

- 回答质量 LLM-as-a-judge；
- Langfuse evaluator 与 evaluation rule；
- 自动规则触发；
- LLM 请求/回复主字段补发；
- 工具执行状态回填。

尚未完成：

- 执行质量 evaluator；
- Dataset 与 Experiment 回归；
- 分维度 judge；
- A/B 偏差与成本统计。

## 2. 架构

```text
Spring AI ChatModel
  -> OTLP span
     -> langfuse.observation.input/output
     -> tool.{i}.name/status
  -> Langfuse 转译
  -> evaluator
  -> evaluation rule
  -> score
```

应用侧有两个关键组件：

| 组件 | 职责 |
|---|---|
| `ChatModelObservationConventionConfig` | 区分 generation 名，补发 LLM 输入和输出 |
| `ToolExecutionRecorder` | 回填工具执行状态 |

## 3. 数据契约

### 3.1 主字段

| 数据 | 主字段 | 来源 |
|---|---|---|
| 用户输入和消息历史 | `input` | `langfuse.observation.input` |
| 模型回复 | `output` | `langfuse.observation.output` |

`ChatContentSerializer` 会把请求消息序列化为角色化 JSON，并处理：

- 普通文本；
- 工具响应；
- assistant tool calls；
- 文本脱敏和截断。

工具调用轮没有文本时，output 会写成：

```text
[调用工具] toolName(arguments)
```

### 3.2 自定义属性

`tool.{i}.name` 和 `tool.{i}.status` 仍回填到 `agent.subagent` span，用于 UI 和后续评估设计。

当前 Langfuse 云的 observation evaluator 无法稳定读取 metadata jsonSelector。因此这些属性不能作为 evaluator 的直接取数源，不能重新尝试 metadata 路径。

## 4. generation 命名

Langfuse 不展示自定义 span attributes 时，调用方语义编码进 generation 名：

```text
phase1-chat
planner-chat
subagent-exec-...
subagent-compress-...
```

当前回答质量 rule 过滤：

```text
type=GENERATION
AND name contains subagent-exec-chat
```

这表示当前评估对象是子 Agent 的最终生成轮，不是完整会话或全过程。

## 5. 回答质量 judge

### 5.1 输入

- 用户问题：observation 主字段 `input`；
- AI 回答：observation 主字段 `output`。

### 5.2 输出

`answer_quality`：

```text
NUMERIC 0-5
```

单综合分，当前没有拆分子维度 score。

### 5.3 judge 模型

| 配置 | 当前值 |
|---|---|
| provider | DashScope OpenAI compatible |
| model | `qwen-turbo` |
| 日期后缀 | 不使用 |
| `useResponsesApi` | `false` |

`useResponsesApi=false` 是 DashScope 正式管道能够通过函数调用返回结构化 judge 结果的前提。

## 6. 应用配置

`agent.evaluation`：

```yaml
agent:
  evaluation:
    enabled: false
    judge-model:
      provider: default
      base-url: ${EVALUATION_LLM_BASE_URL:}
      api-key: ${EVALUATION_LLM_API_KEY:}
      model: ${EVALUATION_LLM_MODEL:qwen-turbo}
```

`provider`：

- `default`：使用 Langfuse 托管 judge；
- `custom`：使用项目配置的 OpenAI-compatible 端点，要求 base-url、api-key、model 完整。

当前实际验证使用 DashScope Connection。`enabled=false` 表示 Java 侧评测功能尚未接入业务触发；Langfuse 云端 evaluator/rule 独立运行。

## 7. 支持范围

### 7.1 已支持

| 能力 | 状态 |
|---|---|
| 主字段 input/output | 已支持 |
| TOOL_CALLS output 序列化 | 已支持 |
| 回答质量 judge | 已支持 |
| rule 自动触发 | 已支持 |
| sampling=1.0 | 已配置 |

### 7.2 待实现

| 能力 | 说明 |
|---|---|
| 执行质量 evaluator | 评估工具选择、调用序列、重试和权限事件 |
| Dataset / Experiment | 用固定案例做批量回归 |
| 分维度评分 | 准确性、完整性、工具使用等独立 score |
| judge 偏差校准 | 当前未做同质模型 A/B |
| 成本核算 | 评估产生的 units 和 judge 调用尚未单独统计 |

## 8. 扩展原则

1. 优先使用 Langfuse 平台 evaluator，不预先在 Java 内构建评测框架。
2. evaluator 只从 observation 主字段取数。
3. 需要过程数据时，先评估能否从 input/output 重建；不能重建再设计新的主字段或 Dataset 数据。
4. judge 输出结构必须与 evaluator `outputDefinition` 一致。
5. 修改 evaluator、rule 或 LLM Connection 后必须跑真实 trace 验证。
6. 不通过 MCP testEvaluator 的成功结果替代正式 rule/Execute 验证。

## 9. 关联文档

- Langfuse 接入和操作见 [Agent 评测排障](runbook/Agent评测排障.md)。
- 观测链路见 `observability/` 下的当前观测文档迁移结果。
