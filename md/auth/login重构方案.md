# Login 模块重构方案（v3）

> 本文档基于现有代码逐行分析 + 架构审查反馈，给出可落地的分阶段重构计划。
>
> 最后更新：2026-06

***

## 1. 现状全景

### 1.1 登录认证调用链路

```
┌─ 浏览器 ─────────────────────────────────────────────────┐
│                                                          │
│  1. POST /user/code?phone=xxx                            │
│  2. POST /user/login { phone, code }  ← 得到 Token       │
│  3. 后续请求 Header: { authorization, Refresh-Token }     │
│                                                          │
└──────────────────────┬───────────────────────────────────┘
                       │
┌─ RefreshTokenInterceptor (order=0, 拦截所有) ─────────────┐
│  ① checkTokenHeader()    ← 检查请求头中两个Token是否存在   │
│  ② JWT 解码 + Claims 提取                                 │
│  ③ 版本号两级校验（Caffeine 快速拒绝 → Redis 最终校验）     │
│  ④ 判断是否临期 → 临期则 Lua 刷新 Token                    │
│  ⑤ 过期则尝试 refreshExpiredToken()                       │
│  ⑥ 设置 UserHolder（userId + userDTO）                    │
│  ⑦ finally: 写回响应头 + 性能日志                          │
└──────────────────────┬───────────────────────────────────┘
                       │
┌─ LoginInterceptor (order=1, 仅拦截需登录接口) ────────────┐
│  检查 UserHolder.getUserId() == null → 401               │
└──────────────────────────────────────────────────────────┘
```

### 1.2 UserServiceImpl.login() 职责分解

当前 `login()` 方法（第69-152行）包含   9 项独立职责  ：

| 步骤 | 代码行     | 职责                   | 问题                        |
| -- | ------- | -------------------- | ------------------------- |
| ①  | 70-74   | 手机号格式校验              | 应前置在 Controller 或 DTO 校验  |
| ②  | 75-78   | 从 Redis 取验证码并比对      | ⚠️   没删除验证码，可重复使用         |
| ③  | 80-95   | 查用户 → 不存在则自动创建       | 创建用户不应放在登录方法中             |
| ④  | 96      | 用雪花ID生成 version      | 属于 Token 生成流程             |
| ⑤  | 97      | JWT 签名生成 AccessToken | 属于 Token 生成流程             |
| ⑥  | 98-108  | 用户信息写入 Redis Hash    | 属于用户缓存逻辑                  |
| ⑦  | 110-117 | 组装 Lua 参数列表          | 属于 Token 存储逻辑             |
| ⑧  | 118-141 | 执行 Lua 脚本写入 Redis    | ⚠️   Lua 返回异常静默吞掉，未返回错误   |
| ⑨  | 142-151 | 组装返回 Map             | Token 通过过过响应体过过返回，与拦截器不一致 |

### 1.3 Token 传递方式不统一

| 场景                 | 传递方式 | 详情                                                 |
| ------------------ | ---- | -------------------------------------------------- |
| 登录接口返回 Token       | 响应体  | `Result.ok({ token: "...", refreshToken: "..." })` |
| Token 刷新后返回新 Token | 响应头  | `response.setHeader("authorization", newToken)`    |
| 前端发送 Token         | 请求头  | `request.getHeader("authorization")`               |

> 后果  ：前端登录时要从响应体取 Token，后续又要从响应头取——两套逻辑。

### 1.4 测试代码混入生产 Service

`UserServiceImpl` 中包含约 200 行的测试代码：

- `TOKEN_LIST`, `PHONE_LIST`, `REFRESHTOKEN_LIST` 三个静态列表
- `generateTestTokenAndRefreshToken()` — 批量生成 Token（含 Redis pipeline）
- `exportTokenAndRefreshTokenToCsv()` — 导出到 CSV
- `generateTestPhone()`, `getTokenList()`, `getPhoneList()` 辅助方法
- `IUserService` 接口中的 `exportTokenAndRefreshTokenToCsv()`

### 1.5 其他问题

| 问题                        | 位置                      | 说明                                                                    |
| ------------------------- | ----------------------- | --------------------------------------------------------------------- |
| `HttpSession` 未使用         | Controller/Service      | 项目早已全面 Token 化，session 参数是遗留物                                         |
| `logout` 空实现              | Controller              | 返回 `"功能未完成"`                                                          |
| 密码登录未实现                   | LoginFormDTO/Service    | `password` 字段存在但 Service 从不使用                                         |
| `GET /user/{id}` 无缓存+无权限  | Controller              | 任何人都可遍历 userId，且无缓存                                                   |
| `GET /user/info/{id}` 无权限 | Controller              | 返回敏感字段（生日、性别），无权限控制                                                   |
| 验证码可重复使用                  | login()                 | GET 后没 DEL，同一验证码可多次登录                                                 |
| 验证码发送无频率限制                | sendCode()              | 可被脚本无限调用                                                              |
| 拼写错误                      | 多处                      | `remaningTime`, `valiateAndGetClaimFromToken`, `fileName = fileName;` |
| 拦截器 20000+ 字节             | RefreshTokenInterceptor | Token 校验+刷新+缓存+日志全部内联                                                 |
| Caffeine `get()` 阻塞       | 拦截器                     | 使用 `get()` 而非 `getIfPresent()`，Redis 抖动时会同步穿透                         |

***

## 2. 目标架构

### 2.1 新增 AuthService + AuthController

```
┌─ AuthController (新增) ──┐    ┌─ UserController ──────────┐
│  POST /auth/login        │    │  POST /user/code          │
│  POST /auth/refresh      │    │  GET  /user/me            │
│  POST /auth/logout       │    │  GET  /user/{id}          │
└──────────┬───────────────┘    │  GET  /user/info/{id}    │
           │                    │  POST /user/sign          │
           │                    │  GET  /user/sign/count    │
           │                    └───────────────────────────┘
           ▼
┌─ AuthService (新增) ──────────────────────────────────┐
│  generateTokenPair(userId)    → TokenPair          ← 纯业务，无HTTP依赖  │
│  validateAccessToken(token)   → ValidationResult                        │
│  refreshTokenPair(refresh)    → TokenPair                               │
│  revokeTokens(userId)         → void     ← logout                       │
│  consumeVerifyCode(phone,code)→ boolean  ← 原子 GET+DEL                 │
└──────────┬──────────────────────────────────────────────────────────────┘
```

### 2.2 AuthService 接口定义

```java
// 新增 DTO
public class TokenPair {
    private String accessToken;
    private String refreshToken;
    private Long version;
}

public class ValidationResult {
    private boolean valid;
    private Long userId;
    private Long version;
    private boolean needsRefresh;
}

// 新增接口
public interface AuthService {
    /** 登录：生成双Token + version（纯业务，HTTP 细节由 Controller 处理） */
    TokenPair generateTokenPair(Long userId);

    /** 校验 Access Token + 版本号 */
    ValidationResult validateAccessToken(String token);

    /** 刷新双Token（临期或过期） */
    TokenPair refreshTokenPair(String refreshToken);

    /** 登出：删除 Redis 中该用户的所有 Token/Version */
    void revokeTokens(Long userId);

    /** 原子消费验证码：GET + DEL，防止重放 */
    boolean consumeVerifyCode(String phone, String code);

    /** 缓存用户信息到 Redis Hash */
    void cacheUserInfo(UserDTO userDTO);
}
```

### 2.3 拦截器目标形态

```
RefreshTokenInterceptor (瘦身):
  preHandle() {
    ① checkTokenHeader()
    ② AuthService.validateAccessToken(token)
          ├─ JWT 解码 + 版本提取
          ├─ Caffeine 快速拒绝 (getIfPresent, 不阻塞)
          └─ Redis 最终校验
    ③ if (临期/过期) AuthService.refreshTokenPair(refreshToken)   ← 纯业务，不传 response
    ④ 设置 UserHolder
    ⑤ 响应头写回 accessToken / refreshToken
  }
  // 性能日志交给 @RecordTime 切面
```

***

## 3. 分阶段实施计划

### Phase 0 — 紧急修复（安全漏洞，立即执行）

| #   | 操作                                                                                   | 文件                                                                                                                                                                                                                                                  | 风险/收益                 |
| --- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- |
| 0.1 | 实现 logout  ：删除 Redis 中 accessToken + refreshToken + version 三个 key                   | UserController + UserServiceImpl                                                                                                                                                                                                                    | 🔴 关掉最高危漏洞，10 分钟      |
| 0.2 | 验证码原子消费  ：新建 Lua 脚本 `ConsumeVerifyCode.lua`，GET + DEL 原子执行，防止同一验证码重复登录               | UserServiceImpl + `resources/ConsumeVerifyCode.lua`                                                                                                                                                                                                 | 🔴 关掉重放攻击，30 分钟       |
| 0.3 | 验证码发送频率限制  ：同一手机号 60 秒内不得重复发送                                                        | UserServiceImpl.sendCode()                                                                                                                                                                                                                          | 🟠 防止短信接口滥用           |
| 0.4 | 移除 HttpSession  ：搜索 `HttpSession session`，全部删除                                       | UserController + IUserService + UserServiceImpl                                                                                                                                                                                                     | 清理遗留参数                |
| 0.5 | 删除被注释的 Dead Code  ：AiService, AiServiceImpl, VoucherOrderServiceImpl                 | service/ + impl/                                                                                                                                                                                                                                    | 清理死代码                 |
| 0.6 | 修复拼写错误                                                                               | RefreshTokenInterceptor:165 `remaningTime`→`remainingTime`；JwtUtil:181 `valiateAndGetClaimFromToken`→`validateAndGetClaimFromToken`；UserServiceImpl:403 `fileName = fileName;` 删除；TokenRefreshCode.java:43 `USEINFO_NOT_FOUND`→`USERINFO_NOT_FOUND` | 消除编译警告                |
| 0.7 | LoginSetToken.lua 去掉先 DEL  ：直接 SET 覆盖——避免主从切换时 DEL 已同步但 SET 未落盘导致 key 丢失             | resources/LoginSetToken.lua                                                                                                                                                                                                                         | 🟡 消除主从故障场景下 key 丢失风险 |
| 0.8 | 验证码改为纯数字  ：`randomString(6)`→`randomNumbers(6)`                                      | UserServiceImpl.sendCode()                                                                                                                                                                                                                          | 真实短信验证码应为纯数字          |
| 0.9 | checkTokenHeader 只校验 authorization  ：去掉 Refresh-Token 必填要求，避免用户因 refresh 过期无法 logout | RefreshTokenInterceptor.checkTokenHeader()                                                                                                                                                                                                          | 🟡 修复 logout 被拦截的问题   |

### Phase 1 — 测试代码剥离 + Token 约定统一

> ⚠️   存量 Token 兼容  ：前端改为同时从响应头和响应体读 Token——`response.headers.get('authorization') || data?.token`，过渡一个版本后再移除响应体逻辑。

| #   | 操作                                                    | 说明                                                                                                                                                                   |
| --- | ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1.1 | 新建 `utils/TokenTestUtil.java`                         | 迁移 `TOKEN_LIST`, `PHONE_LIST`, `REFRESHTOKEN_LIST`, 所有测试方法                                                                                                           |
| 1.2 | `IUserService` 移除 `exportTokenAndRefreshTokenToCsv()` | 接口只保留生产方法                                                                                                                                                            |
| 1.3 | `UserServiceImpl` 删除测试代码                              | 清理约 200 行                                                                                                                                                            |
| 1.4 | 更新 `TestServiceImpl` 调用                               | 改为调用 `TokenTestUtil`                                                                                                                                                 |
| 1.5 | 统一 Token 约定  ：登录接口改为通过过过响应头过过返回 Token                 | UserController.login() + UserServiceImpl.login() 改为 `response.setHeader("authorization", token)` + `response.setHeader("Refresh-Token", refreshToken)`，，，不再通过响应体返回，， |
| 1.6 | 同步更新 `前端开发文档.md`                                      | Token 获取方式改为 `response.headers.get('authorization')`                                                                                                                 |

### Phase 1.5 — 单元测试（Phase 2 前置条件）

> Phase 2 抽取 AuthService 属于高风险重构，必须在此之前为核心路径建立测试覆盖。

| #     | 测试项                   | 内容                                                                         |
| ----- | --------------------- | -------------------------------------------------------------------------- |
| 1.5.1 | JWT 生成+解析往返测试         | `JwtUtil.generateToken()` + `valiateAndGetClaimFromToken()` 往返验证，确保签名/验签一致 |
| 1.5.2 | Token 刷新 Lua 测试       | 模拟过期/临期场景，验证 Lua 返回码                                                       |
| 1.5.3 | 版本号校验测试               | version 新/旧/不存在的三种场景                                                       |
| 1.5.4 | login() 流程测试          | 验证码正确/错误、用户存在/不存在、Lua 成功/失败                                                |
| 1.5.5 | logout() 测试           | 登出后原 token 请求返回 401                                                        |
| 1.5.6 | checkTokenHeader() 测试 | 只有 authorization 无 Refresh-Token 时放行，两者皆无时 401                             |

### Phase 2 — 抽取 AuthService

> ✅ **已落地并演进（P2 认证域拆分，见《后端架构拆分方案》`e2325f2`~`5e23915`）**：
> AuthService/AuthServiceImpl 后续进一步拆为 TokenService（生成/校验/刷新/吊销）、
> SessionContextService、VerifyCodeService、PasswordService + LoginStrategy 策略族
> （密码/验证码登录）+ UserAccountService（注册/查询）；`AuthServiceImpl` 已删除（369→0）。

| #   | 操作                                     | 文件                      | 说明                                                                    |
| --- | -------------------------------------- | ----------------------- | --------------------------------------------------------------------- |
| 2.1 | 新建 `dto/TokenPair.java`                | dto/                    | 含 accessToken, refreshToken, version                                  |
| 2.2 | 新建 `dto/ValidationResult.java`         | dto/                    | 含 valid, userId, version, needsRefresh                                |
| 2.3 | 新建 `service/AuthService.java`          | service/                | 接口定义（5 个方法）                                                           |
| 2.4 | 新建 `service/impl/AuthServiceImpl.java` | service/impl/           | 实现 Token 生成/校验/刷新/验证码消费/用户缓存                                          |
| 2.5 | 修改 `UserServiceImpl.login()`           | UserServiceImpl         | 简化为：①校验手机号→②消费验证码→③查/创建用户→④调 AuthService.generateTokenPair()→⑤返回      |
| 2.6 | 修复 Lua JSON 解析                         | AuthServiceImpl         | Lua 返回 null 或 code != 1 时返回错误，不静默吞掉                                   |
| 2.7 | Caffeine 改为 getIfPresent               | RefreshTokenInterceptor | 避免 Redis 抖动时同步阻塞 Tomcat 线程                                            |
| 2.8 | 删除 LOGIN\_USERINFO\_MAP 死写入            | UserServiceImpl.login() | `login:userinfo:{id}` 从未被读取，统一走 BatchLoadCache 的 `cache:userinfo:` 路径 |
| 2.9 | 统一 RedisIdWorker 注入方式                  | RedisIdWorker           | 移除带参构造器，只用 @Resource 字段注入                                             |

### Phase 3 — 权限 + 缓存增强

| #   | 操作                           | 说明                                                                                                                                         |
| --- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| 3.1 | `GET /user/{id}` 增加权限校验      | 仅当前登录用户（`UserHolder.getUserId() == id`）可查，否则 403                                                                                           |
| 3.2 | `GET /user/info/{id}` 增加权限校验 | 同上，保护 `gender`, `birthday` 等敏感字段                                                                                                           |
| 3.3 | `GET /user/{id}` 增加缓存        | 参考 ShopServiceImpl 的 CacheClient 模式，减少数据库查询                                                                                                |
| 3.4 | 实现密码登录                       | `password` 字段逻辑：注册时 bcrypt 加密存入，登录时比对。。。安全要求。。：密码最少 8 位含字母+数字；同一 IP 每分钟最多 5 次失败；同一账户连续失败 10 次锁定 30 分钟。。。修改密码时 bump version 使旧 Token 全部作废。。 |

### Phase 4 — 拦截器瘦身

| #   | 操作                                                   | 说明                                                    |
| --- | ---------------------------------------------------- | ----------------------------------------------------- |
| 4.1 | Token 解析+版本校验委托给 `AuthService.validateAccessToken()` | 替代内联 JWT 解析 + 两级缓存校验逻辑                                |
| 4.2 | 刷新逻辑委托给 `AuthService.refreshTokenPair()`             | 替代 `refreshDeadlineToken()` + `refreshExpiredToken()` |
| 4.3 | 性能日志移入 `@RecordTime` 切面                              | 已有注解，拦截器 finally 块中移除                                 |
| 4.4 | 响应头写回逻辑统一在 Controller 层处理，不进入 Service                | 拦截器只调 AuthService 获取 TokenPair，由 Controller 写响应头      |

***

## 4. 文件变更清单

| 操作     | 文件                                          | Phase                |
| ------ | ------------------------------------------- | -------------------- |
| 🆕 新建  | `service/AuthService.java`                  | P2                   |
| 🆕 新建  | `service/impl/AuthServiceImpl.java`         | P2（✅ 后续 P2 认证域拆分时删除，职责由 TokenService/VerifyCodeService/PasswordService/LoginStrategy 承接）                   |
| 🆕 新建  | `dto/TokenPair.java`                        | P2                   |
| 🆕 新建  | `dto/ValidationResult.java`                 | P2                   |
| 🆕 新建  | `utils/TokenTestUtil.java`                  | P1                   |
| 🆕 新建  | `resources/ConsumeVerifyCode.lua`           | P0                   |
| ✏️ 修改  | `controller/UserController.java`            | P0/P1/P3             |
| ✏️ 修改  | `service/IUserService.java`                 | P1                   |
| ✏️ 修改  | `service/impl/UserServiceImpl.java`         | P0/P1/P2             |
| ✏️ 修改  | `interceptor/RefreshTokenInterceptor.java`  | P2/P4                |
| ✏️ 修改  | `interceptor/LoginInterceptor.java`         | 不改                   |
| ✏️ 修改  | `config/MvcConfig.java`                     | 不改                   |
| ✏️ 修改  | `utils/security/JwtUtil.java`               | P0（修拼写）              |
| ✏️ 修改  | `md/前端开发文档.md`                              | P1（同步 Token 约定）      |
| ✏️ 修改  | `resources/LoginSetToken.lua`               | P0（SET 覆盖替代 DEL+SET） |
| 🗑️ 删除 | `service/AiService.java`                    | P0                   |
| 🗑️ 删除 | `service/impl/AiServiceImpl.java`           | P0                   |
| 🗑️ 删除 | `service/impl/VoucherOrderServiceImpl.java` | P0                   |
| ✏️ 修改  | `utils/redis/RedisIdWorker.java`            | P2（统一注入方式）           |

***

## 5. 优先级与工作量

```
Phase 0 ─── 紧急安全修复 ─── 9个子任务 ─── 约 1.5h ─── 🔴 立即执行
Phase 1 ─── 测试剥离+Token统一   6个子任务 ─── 约 1h  ─── 🟡 与P0可并行
Phase 1.5 ─ 单元测试              6个测试项 ─── 约 2h  ─── 🟡 Phase 2 前置条件
Phase 2 ─── AuthService抽取      9个子任务 ─── 约 4h  ─── 🟡 核心重构
Phase 3 ─── 权限+缓存增强        4个子任务 ─── 约 2h  ─── 🟢 后续优化
Phase 4 ─── 拦截器瘦身           4个子任务 ─── 约 1h  ─── 🟢 后续优化
```

***

## 6. 验收标准

| Phase | 验收方式                                          |
| ----- | --------------------------------------------- |
| P0    | ✅ `POST /user/logout` 返回成功，原 Token 请求返回 401   |
| P0    | ✅ 同一验证码只能使用一次，第二次返回"验证码错误"                    |
| P0    | ✅ 同一手机号 60 秒内重复请求验证码返回"发送太频繁"                 |
| P0    | ✅ `mvn compile` 通过，无 HttpSession 引用           |
| P0    | ✅ 无 Refresh-Token 时 logout 仍可正常调用             |
| P1    | ✅ 生产 Service 无测试代码，`TokenTestUtil` 可独立调用      |
| P1    | ✅ 登录接口返回头携带 `authorization` + `Refresh-Token` |
| P1.5  | ✅ 5 个核心测试项全部通过，覆盖 JWT/Lua/版本号/login/logout    |
| P2    | ✅ `AuthService` 独立完成 Token 全生命周期管理            |
| P2    | ✅ Lua 异常时返回错误消息，不静默吞掉                         |
| P2    | ✅ Caffeine 使用 `getIfPresent` 不阻塞              |
| P3    | ✅ 非本人查询 `GET /user/{id}` 返回 403               |
| P3    | ✅ 非本人查询 `GET /user/info/{id}` 返回 403          |
| P4    | ✅ 拦截器代码量减少 50%+，`@RecordTime` 接管耗时日志          |

***

## 7. 与前端对接注意事项

### 7.1 Token 获取方式变更（Phase 1 起生效）

```js
// 旧方式（v2 及之前）—— 从响应体取
const data = await response.json();
localStorage.setItem('token', data.data.token);
localStorage.setItem('refreshToken', data.data.refreshToken);

// 新方式（Phase 1 后）—— 从响应头取
localStorage.setItem('token', response.headers.get('authorization'));
localStorage.setItem('refreshToken', response.headers.get('Refresh-Token'));
```

### 7.2 logout 调用（Phase 0 起可用）

```js
// 用户退出时
await fetch('/user/logout', {
    method: 'POST',
    headers: {
        'authorization': localStorage.getItem('token'),
        'Refresh-Token': localStorage.getItem('refreshToken')
    }
});
localStorage.removeItem('token');
localStorage.removeItem('refreshToken');
window.location.href = '/login';
```

### 7.3 CORS

项目已配置 `CorsConfig`，开发期允许所有来源跨域，无需前端代理。

***

## 8. 架构审查追踪

| 审查问题               | 严重程度  | 在 v3 中如何解决                                |
| ------------------ | ----- | ----------------------------------------- |
| 验证码可重复使用           | 🔴 P0 | 0.2：新建 `ConsumeVerifyCode.lua` 原子 GET+DEL |
| logout 未实现         | 🔴 P0 | 0.1：提到 Phase 0，10 分钟实现                    |
| GET /user/{id} 无权限 | 🔴 P0 | 3.1：仅本人可查                                 |
| Caffeine get() 阻塞  | 🟠 P1 | 2.7：改为 `getIfPresent()`                   |
| Token 传递不统一        | 🟠 P1 | 1.5-1.6：提前到 Phase 1 统一为响应头                |
| Lua JSON 解析脆弱      | 🟡 P2 | 2.6：Lua 异常时返回错误消息                         |
| 方案二破坏无状态原则         | 🟠 P1 | v3   已删除方案二  ，仅保留响应头方案                    |
| info() 接口无权限       | 🟡 P2 | 3.2：补充权限校验                                |
| 验证码无限频             | 🟡 P2 | 0.3：60 秒频率限制                              |
| 拼写错误               | 🔵 P3 | 0.6：修复                                    |

***

## 9. 拆分执行建议

建议按以下顺序执行，每完成一个 Phase 提交一次：

```
commit 1: "fix: implement logout, atomic verify code, rate limit, and checkTokenHeader" → Phase 0
commit 2: "refactor: extract test utilities and unify token delivery"                  → Phase 1
commit 3: "test: add unit tests for JWT, Lua, version, login, logout"                  → Phase 1.5
commit 4: "feat: add AuthService for token lifecycle management"                        → Phase 2
commit 5: "fix: add auth check and cache for user endpoints"                            → Phase 3
commit 6: "refactor: slim down RefreshTokenInterceptor"                                 → Phase 4
```

***

## 10. 回滚策略

| Phase | 回滚方式                                                                | 风险                 |
| ----- | ------------------------------------------------------------------- | ------------------ |
| P0-P1 | `git revert <commit-hash>` + 重新部署                                   | 低，改动独立无依赖          |
| P2    | 新旧共存部署  ：先新增 AuthService 但不删旧逻辑 → 验证无误 → 再切流量到新路径 → 删旧代码。回滚时切回旧路径即可 | 中，需额外部署步骤          |
| P3    | `git revert` + 重新部署                                                 | 低，仅 Controller 层变更 |
| P4    | `git revert` + 重新部署。拦截器是独立组件，不影响业务逻辑                                | 低                  |

