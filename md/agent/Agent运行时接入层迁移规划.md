---
status: draft
source_of_truth:
superseded_by:
---

# Agent 运行时接入层迁移规划

> 本文是实施规划，不是当前架构事实源。当前已实现链路仍以
> [Agent 模块架构设计](Agent模块架构设计.md) 和
> [Agent 执行链设计](Agent执行链设计.md) 为准。
>
> 本规划只解决“如何把可替换运行时接进现有后端”，不重新实现 Graph、状态机、
> checkpoint、interrupt 或工具调度框架。

## 实施状态

- A1 已完成初步落地：`AgentRuntime`、`AgentCommand`、`AgentAccessService`。
- A2 已完成初步落地：`LegacyAgentRuntime`、`AgentV2Controller`，旧聊天入口复用接入服务。
- A3 已完成初步落地：引入 Alibaba Graph Core，提供 `GraphAgentRuntime` 最小骨架。
- A4 已完成第一阶段：Graph 模式支持 `respond` 节点，并按 `StreamingOutput` 逐段推送 SSE。
- 当前 V2 只提供 `/agent/v2/string/send`。
- 当前默认运行时仍为 `legacy`；`graph` 模式暂不包含工具、规划和审批。
- `confirm`、`reject`、工具节点、审批 checkpoint 仍待后续实施。

## 1. 目标

在现有 Spring Boot 项目中增加一个很薄的 Agent 接入层：

```text
Controller
  -> AgentAccessService
      -> AgentRuntime
          -> LegacyAgentRuntime
          -> GraphAgentRuntime（后续）
```

其中：

- Alibaba Graph 是 Graph Runtime，负责节点、边、循环、状态和 checkpoint。
- Java 接入层只负责请求归一化、依赖注入、SSE 返回和运行时选择。
- 现有工具、Guard、权限、证据和业务 Service 继续复用。
- 旧 Agent 代码不重写，只在迁移期作为 legacy 实现保留。

## 2. 明确不做

以下内容不在第一版接入层中实现：

- `AgentRuntimeRegistry`
- `AgentProvider`
- `ToolExecutionRegistry`
- 自造 Graph Engine
- 自造 State / Checkpoint / Interrupt
- 自造 Node / Edge / Channel 体系
- 为每个内部类创建接口
- 把整条旧链路重新包成一套新框架

如果后续没有第二个真实实现，不提前增加抽象。

## 3. 最小文件结构

第一版只新增必要文件：

```text
com/hmdp/agent
├── access
│   ├── AgentRuntime.java
│   ├── AgentCommand.java
│   ├── AgentAccessService.java
│   ├── AgentV2Controller.java
│   └── LegacyAgentRuntime.java
│
└── runtime
    └── graph
        └── GraphAgentRuntime.java   # 接入 Alibaba Graph 后再新增
```

`LegacyAgentRuntime` 负责把接入层请求转交现有 `AiService` 链路，不复制规划、
工具循环或 DAG 逻辑。

`GraphAgentRuntime` 后续负责把请求接入 Alibaba Graph，并通过 Graph 的
stream、interrupt 和 checkpoint 能力完成运行时行为。

## 4. AgentRuntime 契约

第一版只保留一个运行时入口：

```java
public interface AgentRuntime {

    /**
     * 执行一次 Agent 对话请求，返回已经装配好的 SSE 连接。
     */
    SseEmitter run(AgentCommand command);
}
```

请求模型：

```java
public record AgentCommand(
        String content,
        String conversationId,
        Long userId
) {
}
```

后续接入审批恢复时，再增加 `resume`，而不是现在提前设计完整生命周期模型。

## 5. 注入方式

接入层使用 Spring 构造器注入接口：

```java
@Service
public class AgentAccessService {

    private final AgentRuntime runtime;

    public AgentAccessService(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    public SseEmitter send(String content, String conversationId) {
        AgentCommand command = new AgentCommand(
                content,
                resolveConversationId(conversationId),
                UserHolder.getUserId()
        );
        return runtime.run(command);
    }
}
```

实现类通过配置选择：

```java
@Component
@ConditionalOnProperty(
        prefix = "agent.access",
        name = "runtime",
        havingValue = "legacy",
        matchIfMissing = true
)
public class LegacyAgentRuntime implements AgentRuntime {
    // 调用现有 AiService 链路
}
```

后续 Graph 实现：

```java
@Component
@ConditionalOnProperty(
        prefix = "agent.access",
        name = "runtime",
        havingValue = "graph"
)
public class GraphAgentRuntime implements AgentRuntime {
    // 调用 Alibaba Graph
}
```

配置：

```yaml
agent:
  access:
    runtime: legacy
```

切换运行时只改配置，不需要新增 Registry，也不需要修改 Controller。

## 6. 入口策略

原有入口：

```text
POST /agent/string/send
POST /agent/confirm
POST /agent/reject
```

第一版接入层先提供：

```text
POST /agent/v2/string/send
```

确认和拒绝接口在运行时迁移到 Graph 审批后，再按同一接入层模式补齐。

这样可以：

- 旧接口保持可用。
- 新运行时独立演进。
- 前端按配置或版本逐步切换。
- 不需要一次性修改旧 Controller。

## 7. Alibaba Graph 接入边界

新 Graph 不使用 `Phase 1`、`Phase 2` 这类线性阶段命名。节点名、边、循环和预算
才是 Graph Runtime 的语义单位；`Phase 1` 只保留在旧链路的当前架构描述中。

接入 Alibaba Graph 后，Java 侧只做适配：

```text
AgentV2Controller
  -> AgentAccessService
      -> GraphAgentRuntime
          -> Alibaba CompiledGraph
              -> Graph Nodes
                  -> 现有工具 / Guard / 权限
```

Alibaba Graph 负责：

- Graph 定义和编译
- 节点和边
- 条件路由
- 循环
- 状态
- interrupt
- checkpoint
- 节点级流式输出

Java 接入层负责：

- HTTP 参数接收
- 用户身份获取
- `AgentCommand` 构建
- SSE 生命周期
- Graph 输出到现有 SSE 协议的映射
- 异常收敛

不把 Alibaba Graph 的类型泄漏到 Controller，也不在 Java 里重写 Graph 的核心能力。

## 8. DAG 的处理

第一版不修改 DAG，也不为 DAG 新增抽象。

旧 DAG 继续作为 legacy 运行时内部实现存在。

只有出现以下需求时，才考虑增加 `ToolExecutionPort`：

- Graph Agent 节点需要复用旧 DAG
- 需要同时支持串行、并行和 DAG 三种策略
- DAG 需要从旧运行时独立出来

届时该接口也只放在 Graph Runtime 内部：

```text
GraphAgentRuntime
  -> ExecuteAgentNode
      -> ToolExecutionPort
          -> DagToolExecution
```

接入层不感知 DAG。

## 9. 分阶段实施

| 阶段 | 目标 | 交付 | 退出标准 |
|---|---|---|---|
| A1 | 最小接入层 | `AgentRuntime`、`AgentCommand`、`AgentAccessService` | 接入层只依赖接口 |
| A2 | 旧链路适配 | `LegacyAgentRuntime`、V2 Controller | 旧兼容接口行为不变，V2 可运行 |
| A3 | Graph 依赖接入 | Alibaba Graph Core、`GraphAgentRuntime` 骨架 | Graph Runtime 可独立启动 |
| A4 | 最小 Graph | Respond、Plan、ExecuteAgent、Finalize | 一条只读查询跑通 |
| A5 | 审批接入 | interrupt、checkpoint、confirm/resume | 审批可恢复 |
| A6 | 内层执行演进 | 复用 DAG 或替换 ReactAgent | 不影响接入层接口 |

## 10. 技术债红线

出现以下情况说明又开始造轮子了：

- Java 侧重新实现 Graph、State、Checkpoint 或 Interrupt
- Controller 直接注入 `GraphAgentRuntime`
- 新旧运行时互相调用
- 一个请求同时由两个运行时管理状态
- 为只有一个实现的内部类提前创建接口
- 为“未来可能扩展”增加 Registry、Provider、Capability
- 两套 SSE 协议长期并存且没有映射层
- DAG 重新承担循环、审批或最终回答职责

控制原则：

> 接口只用于真实存在的实现替换。
> Alibaba Graph 负责运行时，Java 代码负责接入和领域能力。

## 11. 验收标准

- 旧 `/agent/string/send` 行为不变。
- 新 `/agent/v2/string/send` 能通过 `AgentRuntime` 接口完成请求。
- 修改 `agent.access.runtime` 即可选择运行时。
- `GraphAgentRuntime` 不调用旧 `MultiRoundOrchestrator`。
- `LegacyAgentRuntime` 不调用 Graph Runtime。
- 接入层不包含规划、工具循环、DAG 和审批状态机。
- 后续增加 Graph Runtime 不需要修改 Controller。

## 12. 关联文档

- [Agent 模块架构设计](Agent模块架构设计.md)
- [Agent 执行链设计](Agent执行链设计.md)
- [Agent 安全与审批设计](Agent安全与审批设计.md)
- [SSE 后端实现规范](SSE后端实现规范.md)
- [Agent 观测设计](Agent观测设计.md)
- [文档规范](../规范/文档规范.md)
