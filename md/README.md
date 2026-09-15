# PickNear 后端文档

> 修改文档前先读 [文档规范](规范/文档规范.md) 和目标模块 `README.md`。

## 模块入口

| 模块 | 入口 | 内容 |
|---|---|---|
| Agent | [agent/README.md](agent/README.md) | 对话、规划、工具、记忆、安全、评测和观测 |
| 认证 | [auth/README.md](auth/README.md) | 验证码、密码、双 Token、刷新和会话 |
| 架构 | [arch/README.md](arch/README.md) | 后端域划分、请求链和基础设施 |
| 运维与接口 | [ops/README.md](ops/README.md) | API、上传、部署、日志和 Langfuse |
| 报告 | [report/README.md](report/README.md) | 审查、优化、压测和面试材料 |
| 规范 | [规范/README.md](规范/README.md) | 文档、Git 和请求头规范 |

## 阅读规则

1. 先在本页选择模块。
2. 进入模块 README 后按任务路由读取具体文档。
3. 不默认通读模块下所有文档。
4. 当前文档以代码、配置、运行时行为和测试为事实源。
5. 历史材料和原始证据不参与默认阅读。

## 文档状态

当前文档使用：

| status | 含义 |
|---|---|
| `current` | 当前有效事实 |
| `draft` | 草稿 |
| `legacy` | 未迁移或已废弃旧档，不进入当前路由 |

历史由 Git 保存，不建立长期 `archive/` 目录。

## 新增约束

1. 默认不新建文档，优先补进已有文档。
2. 新建文档前必须确认现有文档无法承载。
3. 新文档必须声明 `status` 并加入模块 README。
4. 当前文档必须具备可检查的相对链接。
5. 修改后运行：

```bash
python scripts/docs/check_docs.py
```
