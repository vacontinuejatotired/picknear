# PickNear（探点） — 项目架构说明

> **前后端分离架构**，但前端和后端代码都放在 `E:\heima\` 工作区下同一层级。

---

## 目录总览

```
E:\heima\                              # 工作区根目录
├── picknear\                          # 【Git 仓库】后端 Spring Boot 项目
│   ├── picknear\                      #   Spring Boot 3.4.4 + Maven + Java 17
│   │   ├── pom.xml
│   │   └── src/
│   ├── md\                            #   架构设计文档
│   ├── logs\                          #   日志
│   └── .claude\                       #   Claude Code 配置
├── nginx-1.18.0heima\                # Nginx 配置 + 前端项目
│   ├── frontend\                      #   【独立 Git 仓库】Vue 3.5 前端
│   │   ├── package.json
│   │   ├── vite.config.ts
│   │   └── src/
│   └── nginx-1.18.0\                 #   Nginx 程序文件
└── nginx-1.18.0heima（已放行）
```

## 架构说明

| 层 | 技术栈 | 位置 | 端口 |
|---|--------|------|------|
| **前端** | Vue 3.5 + Vite 8 + TypeScript 6 + Element Plus | `E:\heima\nginx-1.18.0heima\frontend\` | 3000 (dev) |
| **后端** | Spring Boot 3.4.4 + Maven + Java 17 | `E:\heima\picknear\picknear\` | 8081 |
| **反向代理** | Nginx 1.18 | `E:\heima\nginx-1.18.0heima\nginx-1.18.0\` | 80 |

## Git 仓库

- **本仓库** (`E:\heima\picknear\`) — 仅跟踪**后端**代码，remote: `git@github.com:vacontinuejatotired/picknear.git`
- **前端仓库** (`E:\heima\nginx-1.18.0heima\frontend\`) — 独立 git 仓库，有单独的 `.git`

## 开发方式

前后端通过 API 通信（axios 请求后端接口），典型工作流：
1. 后端：在 `E:\heima\picknear\picknear\` 下启动 Spring Boot 服务
2. 前端：在 `E:\heima\nginx-1.18.0heima\frontend\` 下 `npm run dev`
3. 生产：Nginx 反向代理，前端静态文件由 Nginx 托管，API 请求转发到后端

## 开发/部署约定（2026-08-30 更新）

- **开发一律在共享文件夹进行**：`/mnt/hgfs/heima`（主机侧 `E:\heima`）是 VMware 共享文件夹，主机写代码 → VM 实时可见，**无需任何拷贝/同步**
- **docker compose 直接用共享文件夹版本**：`~/.bashrc` 的 `COMPOSE_FILE` 已指向 `/mnt/hgfs/heima/picknear/picknear/docker-compose.yml`
- **镜像构建已迁到 CI/CD**：`docker-compose.yml` 里 app/frontend 服务**不 build 本地镜像，从阿里云 ACR 拉取**（`build-image.yml` 构建推送）。改完代码的部署链路：push 后端 `feature` → 合并到 `master` → push 自动触发 Build Image → 镜像推 ACR → VM `docker compose pull && docker compose up -d --no-build`。**不要在本机/VM `docker build` / `docker compose build`**（本地构建镜像与 ACR 脱节，且 hgfs 偶发 short read）。详见 `md/ops/Docker部署指南.md`
- **`/opt/picknear` 是旧部署副本**（2026-08-01 按 `md/ops/Docker部署指南.md` 的 scp 流程创建，配套 `vm-docs/deploy-vm.sh`，VM 部署文档已归档到 `vm-docs/`），代码是静态拷贝、**不随主机更新，不再用于开发**。踩坑史：2026-08-03 曾因 `COMPOSE_FILE` 指向它，compose 一直构建旧代码（镜像缺失 observability 模块），已改回共享文件夹
- 密钥与云服务凭据在 `picknear/picknear/.env`（compose 同级自动读取，已被 .gitignore 忽略），改动后可用 `docker compose config` 校验注入

## 注意事项

- 修改前端代码请到 `E:\heima\nginx-1.18.0heima\frontend\`（那里有自己的 CLAUDE.md 详细说明前端的架构约定和代码规范）
- 修改后端代码请在本目录下 `picknear/` 子目录操作
- 不要混淆前后端的 git 仓库操作

## 本地 Maven 编译问题（Windows 共享文件夹）

### 问题原因（非 VSCode，是 VMware HGFS 权限）

`E:\heima\` 是 VMware 共享文件夹。**根因是 VM 里的 Linux Maven 编译产出的 `target/` 目录文件权限为 Linux 默认（`-rw-r--r--`），通过 HGFS 映射到 Windows 时，Windows 侧只有读权限，无法写入/删除。** 与 VSCode 无关。

验证方法：`E:\heima\` 根目录和 `picknear/` 目录都可写，唯独 `target/` 不可写——因为 `target/` 是 VM 里 Maven 创建的。

### 一次性修复

**方式 A（推荐）：启动 VM，在 VM 里执行**
```bash
sudo rm -rf /mnt/hgfs/heima/picknear/picknear/target
```

**方式 B：以管理员身份在 Windows PowerShell 执行**
```powershell
takeown /F "E:\heima\picknear\picknear\target" /R /A /D Y
icacls "E:\heima\picknear\picknear\target" /grant "Ntwitm:(OI)(CI)(M)" /T
Remove-Item -Recurse -Force "E:\heima\picknear\picknear\target"
```

### 预防措施

每次在 VM 里跑完 Maven 后，修复权限防止下次 Windows 侧编译失败：
```bash
sudo chmod -R 777 /mnt/hgfs/heima/picknear/picknear/target
```

### 临时绕行方案

如果暂时无法修复 `target/`，编译时复制到 `%TEMP%` 再编译，编完删除：
```powershell
$tempDir = "$env:TEMP\picknear-compile"
Copy-Item -Recurse "E:\heima\picknear\picknear\src" "$tempDir\src"
Copy-Item "E:\heima\picknear\picknear\pom.xml" "$tempDir\pom.xml"
cd $tempDir && mvn test -Dtest="..." -DfailIfNoTests=false
Remove-Item -Recurse -Force $tempDir  # 编完务必删除
```
