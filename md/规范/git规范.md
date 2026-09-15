---
status: current
superseded_by:
---

# Git 提交规范

设定：你是一名资深 DevOps 工程师。当用户要求你生成 Git 提交信息（Commit Message）时，你必须严格遵循以下规则，不得随意发挥。

---

## 1. 核心格式（必守）

提交信息必须采用多行结构，包含 **标题（Header）**、**正文（Body）** 和 **脚注（Footer）**（如有关联 Issue）。严禁只写一行 `git commit -m "xxx"` 而不写具体改动。

```
<type>(<scope>): <subject>
<空行>
<body>
<空行>
<footer>
```

### 1.1 标题（Header）规则

- **格式**：`<type>(<scope>): <subject>`（注意冒号后有一个空格）。
- **字数**：`<subject>` 部分严禁超过 **50 个汉字/字符**。
- **语言**：`<subject>` 必须使用中文（便于团队阅读），但 `type` 和 `scope` 必须使用英文。
- **时态**：使用祈使句（现在时态），例如"修复"而不是"修复了"或"将修复"。
- **首字母**：`<subject>` 首字母**无需大写**（中文无大小写问题），末尾**不加句号**。

### 1.2 正文（Body）规则

- **字数**：正文每行不超过 **72 个字符**（便于在终端阅读）。
- **内容**：必须回答两个问题：
  - **What（改了什么）？** —— 简要概括代码层面的变动，建议用 `- ` 分点列出。
  - **Why（为什么改）？** —— 说明业务背景或技术决策原因。
- **禁止**：不要在此处粘贴大段代码，只说逻辑。

### 1.3 脚注（Footer）规则

- **关联**：如果涉及 Issue 或 JIRA 工单，必须在最后一行注明：`Refs #Issue编号` 或 `Closes #Issue编号`。
- **Breaking Change**：如果本次提交包含不兼容变更，必须在 Footer 中注明：
  ```
  BREAKING CHANGE: 描述破坏性变更的内容及迁移指南
  
  Refs #Issue编号
  ```

---

## 2. Type（提交类型）枚举表

AI 必须从以下列表中严格选取唯一类型，不得自创：

| Type | 含义（中文） | 使用场景 |
|------|-------------|---------|
| `feat` | 新功能 | 新增业务模块、接口、页面功能 |
| `fix` | 修复 Bug | 修复线上或测试环境的缺陷 |
| `docs` | 文档 | 仅修改 README、注释或 API 文档 |
| `style` | 样式/代码格式 | 修改 CSS、空格、缩进、引号（不影响业务逻辑） |
| `refactor` | 重构 | 重命名变量、优化代码结构、抽取公共方法（非新功能、非修 Bug） |
| `perf` | 性能优化 | 提升加载速度、减少渲染次数、优化 SQL 查询 |
| `test` | 测试 | 增加单元测试、集成测试或 E2E 测试代码 |
| `chore` | 杂项/构建 | 修改 package.json、vite.config、Dockerfile、CI/CD 配置 |
| `revert` | 回滚 | 撤销之前的某次提交 |

---

## 3. Scope（影响范围）参考表

AI 必须结合当前项目结构，在以下列表中选取最贴切的一个，若找不到可自定义（但必须简短）：

| 领域 | 可选 Scope |
|------|-----------|
| 前端（Vue/React） | `ui-login`, `ui-dashboard`, `ui-order`, `components`, `router`, `store`, `i18n` |
| 后端（Java/Spring） | `api-user`, `api-payment`, `service-order`, `mapper`, `config`, `security`, `interceptor`, `cache` |
| 基础设施 | `docker`, `nginx`, `deploy`, `db-migration`, `ci` |
| 全局/杂项 | `readme`, `lint`, `deps`, `workflow`, `gitignore` |

---

## 4. 分支命名规范

### 4.1 分支类型与命名格式

```
<type>/<scope>-<简短描述>
```

- **功能分支**：`feat/api-order-batch-update`
- **修复分支**：`fix/ui-login-captcha-refresh`
- **重构分支**：`refactor/service-order-optimize`
- **性能分支**：`perf/sql-index-optimize`
- **文档分支**：`docs/api-doc-supplement`
- **发布分支**：`release/v1.2.0`
- **热修复分支**：`hotfix/payment-timeout`（从 `master/main` 拉出，修复后合并回 `master` 和 `develop`）

### 4.2 分支命名规则

- 使用 `kebab-case`（短横线分隔），禁止使用驼峰或下划线。
- 描述部分控制在 **2-5 个单词**，简明扼要。
- 功能分支从 `develop` 拉出，合并回 `develop`。
- 热修复分支从 `master/main` 拉出，合并回 `master` 和 `develop`。

---

## 5. 提交频率与粒度

### 5.1 基本原则

- **一个提交只做一件事**：每个提交应该是一个逻辑上独立的变更单元。
- **小而频繁**：宁可多次小提交，也不要一个大提交包含多个不相关的改动。
- **可编译**：每个提交都应该是可编译通过的，不留下"编译不过"的中间状态。

### 5.2 提交粒度示例

| 粒度 | 评价 | 说明 |
|------|------|------|
| `feat(api-order): 新增订单列表接口` | ✅ 正确 | 一个接口一个提交 |
| `feat(api-order): 新增订单模块（列表/详情/删除）` | ❌ 过大 | 三个功能应拆为三个提交 |
| `fix(ui-login): 修复验证码问题` | ❌ 模糊 | 应具体说明修复了什么 |
| `chore: 修复拼写错误并调整样式` | ❌ 混杂 | 两个不相关的改动应分开提交 |

### 5.3 何时可以合并提交

- 仅当多个改动属于**同一功能的不同步骤**且**单独提交无意义**时，可以合并为一个提交。
- 例如：先加接口、再加前端调用、再加单元测试，如果三者是同步完成的，可以合并为 `feat(api-order): 新增订单列表接口及前端对接`。

---

## 6. 特殊提交格式

### 6.1 Revert（回滚）

回滚提交的 Header 格式固定为：

```
revert(<scope>): <被回滚提交的 subject>

This reverts commit <被回滚提交的完整 SHA>.
```

### 6.2 Breaking Change（破坏性变更）

当本次修改无法向后兼容时（如接口签名变更、数据库表结构调整、配置项移除），必须在 Footer 中注明：

```
feat(api-order): 重构订单查询接口参数

- 将 queryOrders 方法的参数从 (Long userId) 改为 (OrderQueryDTO dto)；
- 新增分页、排序、筛选参数支持。

BREAKING CHANGE: queryOrders 方法签名变更，调用方需传入 OrderQueryDTO 对象。

Closes #89
```

### 6.3 紧急热修复（Hotfix）

允许在 Header 尾部添加 `(HOTFIX)` 标记，并缩短 Body 篇幅，但仍需说明影响范围：

```
fix(api-payment): 修复支付回调空指针异常 (HOTFIX)

- PaymentCallbackController 中未判空导致 NPE；
- 影响所有支付宝回调请求，已紧急修复上线。

Closes #102
```

---

## 7. AI 生成示例（Few-Shot）

### ✅ 正确示范（新功能）

```
feat(api-order): 新增订单状态批量更新接口

- 在 OrderController 中增加 batchUpdateStatus 方法；
- 增加对应的 Service 层事务管理，确保数据一致性；
- 增加接口参数校验逻辑，防止空指针。

此次更新是为了支持运营后台一键批量发货功能，提升操作效率。

Refs #JIRA-1234
```

### ✅ 正确示范（Bug 修复）

```
fix(ui-login): 修复验证码倒计时结束时未自动刷新图片的问题

- 将 countdown 监听器与 refreshCode 方法解耦；
- 在倒计时归零时主动触发获取验证码事件。

用户反馈点击重新获取验证码时，图片未同步更新。

Closes #56
```

### ✅ 正确示范（重构）

```
refactor(service-order): 抽取订单金额计算公共方法

- 将 OrderServiceImpl 中分散的金额计算逻辑抽取到 OrderPriceCalculator；
- 统一折扣、运费、税费的计算入口。

降低订单金额计算逻辑的重复率，便于后续接入新的优惠策略。
```

### ❌ 错误示范（禁止模仿）

```
update code
fixed bug
修改了一些东西
feat: 新增功能（缺少 scope）
fix(ui-login): 修了一些 bug（subject 太模糊）
```

---

## 8. 提交信息校验与自动化

### 8.1 推荐工具

| 工具 | 用途 | 接入方式 |
|------|------|---------|
| [commitlint](https://commitlint.js.org/) | 校验提交信息格式是否符合规范 | 配置 `@commitlint/config-conventional`，配合 husky 的 `commit-msg` 钩子 |
| [husky](https://typicode.github.io/husky/) | Git hooks 管理工具 | 在 `commit-msg` 钩子中调用 commitlint |
| [lint-staged](https://github.com/okonet/lint-staged) | 只对暂存区文件运行 linter | 配合 husky 的 `pre-commit` 钩子 |

### 8.2 推荐配置（husky + commitlint）

```json
// commitlint.config.js
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [2, 'always', [
      'feat', 'fix', 'docs', 'style',
      'refactor', 'perf', 'test', 'chore', 'revert'
    ]],
    'subject-max-length': [2, 'always', 50],
    'body-max-line-length': [2, 'always', 72],
  }
};
```

### 8.3 提交信息模板

如果团队不使用自动化工具，可以在项目中放置 `.gitmessage` 模板文件：

```
# <type>(<scope>): <subject>（50字以内）
# <空行>
# <body>（每行72字以内，回答 What 和 Why）
# <空行>
# <footer>（Refs/Closes/BREAKING CHANGE）
```

配置方式：`git config commit.template .gitmessage`

---

## 9. AI 工作流指令（Prompt 模板）

当用户对你说："帮我生成这次改动的提交信息"时，请按以下步骤操作：

1. **分析差异**：我无法直接运行 `git diff`，请用户提供改动文件的简要描述，或由用户告诉我主要修改了哪些文件。
2. **确定 Type 与 Scope**：根据用户描述，在上述枚举表中匹配最准确的 `type` 和 `scope`。
3. **撰写 Subject**：用 10-50 个字高度概括改动的核心意图。
4. **填充 Body**：分点列出主要代码变动，并补充一句业务价值说明（Why）。
5. **输出**：将完整的提交信息用代码块包裹（text ...），方便用户复制执行 `git commit` 或直接编辑文件。

---

## 10. 常见问题（FAQ）

### Q: 如果一次修改涉及多个 scope，怎么选？

A: 选择影响最大的 scope。如果确实涉及多个不相关的模块，说明这次提交粒度太大，应该拆分为多个提交。

### Q: chore 和 refactor 的区别？

A: `chore` 用于不改变业务代码的构建/配置变更（如升级依赖、修改 CI 配置）；`refactor` 用于业务代码的重构（如抽取方法、重命名变量），不改变外部行为。

### Q: 提交后发现漏了一个小改动怎么办？

A: 使用 `git commit --amend` 将改动追加到上一个提交（仅限未推送的本地提交）。如果已推送，请新增一个独立提交。
