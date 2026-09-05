# Agent 模块发展路线图

> **版本**: v3.0（精简版）  
> **创建**: 2026-07-22  
> **最后更新**: 2026-08-23 — 精简为状态总览，详细设计已过时的章节已移除  
> **相关文档**: [Agent模块架构设计](./Agent模块架构设计.md), [Agent模块链路迭代文档](./Agent模块链路迭代文档.md)

---

## 落地状态总览

| Phase | 目标 | 状态 | 落地方式 |
|-------|------|------|----------|
| **Phase 0** 清理与加固 | 消除死代码、修复缺陷 | ✅ 完成 | PromptGuard 删除、线程池配置、SSE JSON 注入修复 |
| **Phase 1** 基础设施补全 | 测试、监控、配置管理 | 🟡 大部分完成 | Guard/Hook/Router 测试 ✅、CONFIRM 审批 ✅、提示词外置 ❌ |
| **Phase 2** 真正 Agent | 多步推理、真流式 | ✅ 完成 | Plan-and-Execute 路径（非 ReAct）、ObservedSseEmitter 真流式 |
| **Phase 3** 业务工具生态 | 丰富工具 | 🟡 进行中 | blog/天气/店铺查询 ✅、VoucherQueryTool ✅、UserQueryTool ✅ |
| **Phase 4** 生产级能力 | 可观测性、缓存 | ✅ 完成 | Langfuse OTLP（替代 Prometheus）、Caffeine 多级缓存 |
| **Phase 5** 智能进化 | RAG、长期记忆 | ❌ 未做 | 远期规划 |

---

## 各 Phase 已完成项

### Phase 0 — 清理与加固 ✅

- `PromptGuard.java` 死代码已删除
- 空端点 `postMethodName` 已删除
- AI 专用线程池 `aiTaskExecutor` 已配置
- SSE JSON 注入漏洞已修复
- SSE conversationId 推送失败处理已修复

### Phase 1 — 基础设施补全

| 项 | 状态 | 说明 |
|----|------|------|
| Guard 层单元测试 | ✅ | ToolGuardManager + 4 个 Policy |
| GuardedToolCallback 测试 | ✅ | BLOCK/CONFIRM/ALLOW 路径 |
| PromptHookChain 测试 | ✅ | PASS/REPLACE/BLOCK/Fail-Open |
| CONFIRM 审批 | ✅ | 真暂停 + agent_approval 表 + 超时/审计 + 前端审批卡片 |
| 历史会话 | ✅ | agent_conversation + agent_message 表、HistoryController |
| 提示词外置 | ❌ | 仍硬编码在 AgentConfig.java，建议迁移至 Langfuse Prompt Management |
| 用户偏好 | ❌ | 未做 |

### Phase 2 — 真正 Agent ✅

- **Plan-and-Execute 路径**（非 ReAct）：TaskPlanner 两阶段 decompose→execute→merge
- **MultiRoundOrchestrator**：最多 5 轮迭代
- **真流式**：`dashScopeChatModel.stream()` + ObservedSseEmitter
- **SSE 事件协议**：meta/progress/error 三类事件 + 阶段常量
- **执行轨迹**：由 Langfuse OTLP 取代自建 agent_trace 表

### Phase 3 — 业务工具生态 🟡

| 工具 | 状态 |
|------|------|
| ShopQueryTool（搜索 + 通用查询） | ✅ |
| VoucherQueryTool（优惠券查询） | ✅ |
| UserQueryTool（用户查询） | ✅ |
| BlogQueryTool（博客查询） | ✅ |
| WeatherQueryTool（天气查询） | ✅ |
| StatsQueryTool（统计查询） | ✅ |

### Phase 4 — 生产级能力 ✅

- **可观测性**：`agent/observability/` 包 + Langfuse 云观测（OTLP）
- **观测后端可插拔**：TraceBackendAssembler 配置驱动
- **多级缓存**：Caffeine → Redis → MySQL
- **Prompt 管理**：Langfuse 远程 → 本地数据库 → 内置三级降级

---

## 遗留项与未来方向

| 项 | 优先级 | 说明 |
|----|--------|------|
| 提示词迁移到 Langfuse | P1 | 当前硬编码在 AgentConfig，应迁至 Langfuse Prompt Management |
| ~~Redis ChatMemory~~ ✅ 已完成 | — | 会话上下文改自研：agent_message 完整落库 + P1 回放 + P2 异步小模型压缩为运行摘要（Redis `Mem` 单 key 视图）；JDBC ChatMemory 空转不再使用。见《上下文压缩子系统设计文档》 |
| RAG 知识库 | P3 | 远期，可基于 agent_message 回灌 |
| 多 Agent 编排 | P3 | 远期规划 |
| 用户偏好学习 | P3 | 基于对话历史自动学习 |

---

## 架构成熟度（2026-08-23）

```
Controller                ██████████ 100%  ✅
Service                   ██████████ 100%  ✅
Guard                     ██████████ 100%  ✅
Permission                ██████████ 100%  ✅
Agent 核心                ██████████ 95%   ✅ Plan-and-Execute + MultiRound
流式体验                  ██████████ 100%  ✅ 真流式 + ObservedSseEmitter
可观测性                  ██████████ 95%   ✅ Langfuse OTLP
工具生态                  ████████░░ 80%   🟡 核心工具已有
Prompt 管理               ████████░░ 80%   ✅ 三级降级（未迁 Langfuse）
安全审批                  ██████████ 100%  ✅ 真暂停 + 审批表
测试                      ████████░░ 75%   🟡 有测试但覆盖不全
```
