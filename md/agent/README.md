# PickNear Agent

> Spring Boot 3.4.4 · Spring AI 1.1.2 · DashScope · Java 17
>
> 本页只负责模块定位、任务路由和迁移状态。执行具体任务时，先按“任务路由”选择文档，不要默认通读本页。

---

## 任务路由

| 我要做什么 | 读取 |
|---|---|
| 理解整体架构和请求主链 | [Agent 模块架构设计](Agent模块架构设计.md) |
| 修改规划、工具循环或 DAG | [Agent 执行链设计](Agent执行链设计.md) |
| 修改上下文、回放或压缩 | [Agent 上下文与记忆设计](Agent上下文与记忆设计.md) |
| 修改 Guard、权限或 CONFIRM | [Agent 安全与审批设计](Agent安全与审批设计.md) |
| 修改数据意图、断言闸或事实账本 | [Agent 诚实机制设计](Agent诚实机制设计.md) |
| 修改评测或排查 judge/rule | [Agent 评测设计](Agent评测设计文档.md) / [Agent 评测排障](runbook/Agent评测排障.md) |
| 本地启动、配置或验证 SSE | [快速开始与配置](runbook/快速开始与配置.md) |
| 修改文档规则或索引 | [文档规范](../规范/文档规范.md) |
| 查看哪些文档仍需迁移 | [迁移状态](#迁移状态) |

## 模块定位

Agent 模块位于 Spring AI 之上，负责把用户请求、工具调用、安全守卫、审批恢复、多轮记忆和观测串成一条可运行的对话链路。

当前主链采用两阶段模式：

1. Phase 1 进行不绑定工具的文本回复。
2. AfterAiHook 决定是否进入规划。
3. Phase 2 由 `PlanRouter` 规划工具，由 `MultiRoundOrchestrator` 编排执行。
4. 执行结果经过 Guard、权限校验和结果压缩后返回并落库。

## 核心能力

| 能力 | 当前实现 |
|---|---|
| 规划与路由 | `PlanRouter` 策略，默认 `TreePlanRouter`，可回退 `LegacyPlanRouter` |
| 多轮编排 | `MultiRoundOrchestrator` 最多 5 轮，支持 CONFIRM 暂停与恢复 |
| 工具循环 | `agent.subtask.tool-loop` 支持 `serial`、`batch`、`hybrid`，当前默认 `hybrid` |
| 工具执行 | `RoundExecutionProxy` → `ToolExecutionFacade` → 重试、结果解析与压缩 |
| 安全控制 | Prompt Hook、Tool Guard、数据权限和 CONFIRM 审批 |
| 对话流式 | SSE-only，事件包含 `meta`、`plan`、`progress`、`error`、`confirm` |
| 多轮记忆 | 历史回放、字符预算裁剪和异步摘要压缩 |
| 可观测性 | `AgentTracer` → OTel → 可插拔后端；默认 Langfuse |
| Prompt 管理 | Langfuse Prompt 与内置模板降级，支持运行期重载 |

## 当前文档

| 主题 | 当前入口 |
|---|---|
| 整体架构 | [Agent 模块架构设计](Agent模块架构设计.md) |
| 规划与执行 | [Agent 执行链设计](Agent执行链设计.md) |
| 上下文与记忆 | [Agent 上下文与记忆设计](Agent上下文与记忆设计.md) |
| 安全与审批 | [Agent 安全与审批设计](Agent安全与审批设计.md) |
| 诚实机制 | [Agent 诚实机制设计](Agent诚实机制设计.md) |
| 评测设计 | [Agent 评测设计](Agent评测设计文档.md) |
| 评测排障 | [Agent 评测排障](runbook/Agent评测排障.md) |
| 启动与配置 | [快速开始与配置](runbook/快速开始与配置.md) |
| 文档治理 | [文档规范](../规范/文档规范.md) |

## 迁移状态

以下内容尚未全部满足当前文档规范。它们在迁移完成前不作为任务路由中的当前事实源。

| 分类 | 当前问题 | 后续动作 |
|---|---|---|
| 核心总文档 | 旧处理流程已由当前架构文档替代 | 后续删除旧稿 |
| 执行链旧稿 | 规划路由、DAG 和任务队列已被当前执行设计替代 | 后续删除旧稿 |
| 上下文与记忆旧稿 | 上下文、优化、压缩和交接稿已被当前设计替代 | 后续删除旧稿 |
| 安全与诚实旧稿 | CONFIRM 与反编造设计已被当前文档替代 | 后续删除旧稿 |
| 评测旧稿 | 评测排障交接稿已被当前 Runbook 替代 | 后续删除旧稿 |
| SSE 与观测 | 协议、架构、解耦方案、Emitter 和操作指南重复 | 合并为协议、观测设计和运行手册 |
| 历史材料 | 迭代记录、耗时记录、完成型交接和一次性排障 | 提取遗留项后删除 |
| 非模块文档 | 前端方案、简历亮点、Langfuse CLI/MCP 工具说明 | 移到前端仓库、`report/` 或 `ops/` |

## 运行原则

1. 代码、配置、运行时行为和测试是事实证据。
2. 当前 README 只维护模块入口，不复制专题文档正文。
3. 新增文档前先确认现有文档无法承载。
4. 迁移完成的历史文档直接删除，历史由 Git 保存。
5. 文档修改后运行 `python scripts/docs/check_docs.py`。

## License

Internal use only.
