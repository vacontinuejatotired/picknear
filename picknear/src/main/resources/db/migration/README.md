# 数据库迁移脚本（Flyway）

## 这是干什么的

`heima-init.sql` 只在 **MySQL 数据卷为空（首次创建）** 时执行，之后的 schema 变更它完全管不着。
所以增量变更统一放这里，由 Flyway 在应用启动时自动执行并记录版本。

## 怎么用

在 `src/main/resources/db/` 目录：`src/main/resources/db/migration/`

1. 新建文件 `V2__简短描述.sql`（**版本号从 2 开始**，见下方"为什么不是 V1"）
2. 写 DDL / DML，**务必保证可重复安全**（用 `IF NOT EXISTS`、`INSERT IGNORE` 等）
3. push 到 master → CI 构建镜像 → 部署时应用启动自动执行，执行记录写进 `flyway_schema_history` 表
4. 已执行过的脚本 **禁止再改**——`validate-on-migrate: true` 会校验 checksum，改了应用直接启动失败

## 为什么从 V2 开始

现有生产库是用 `heima-init.sql` 建好的，此前没有任何版本记录。
`application-prod.yaml` 里配了 `baseline-on-migrate: true` + `baseline-version: 1`，
Flyway 首次接管时会把当前 schema 记为 **版本 1**（只插一条历史记录，不执行任何脚本），
所以你的第一个增量脚本是 `V2__`。

## ⚠️ 两个雷

**1. 不要把 `heima-init.sql` 拷进来改成 `V1__xxx.sql`。**
那个文件是 mysqldump 产物，开头全是 `DROP TABLE IF EXISTS`，放进迁移目录跑一次就是**删库**。
它的唯一用途是全新环境的 bootstrap（compose 挂到 `/docker-entrypoint-initdb.d/`）。

**2. 别在脚本里写 `CREATE DATABASE` / `USE`。**
Flyway 已经连着 `heima` 库执行，写了会报错或作用到错误的库。

## 查看状态

```bash
docker compose exec mysql mysql -uroot -p"$DB_PASSWORD" heima \
  -e "SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
```
