# 过期 Token 刷新流程

> 2026-08-05 更新：`refreshTokenPair` 改为 Lua 原子刷新（复用 `RefreshExpiredToken.lua`），
> `newVersion = oldVersion`（版本号不 bump，版本一致性由 Lua 守卫保证）；
> 新增 422 并发容错（`ReadCurrentToken.lua` 原子读取当前凭证，内含版本守卫）。

```mermaid
flowchart TD
  A[收到过期token<br/>拦截器标记 isExpired=true] --> B[refreshTokenPair<br/>accessToken + refreshToken + oldVersion]
  B --> C{refreshToken 非空？}
  C -->|否| D[返回 null] --> D1[拦截器 401 + X-Auth-Reason]

  C -->|是| E[生成新双 token<br/>newVersion = oldVersion<br/>不 bump 版本号]
  E --> F[执行 RefreshExpiredToken.lua<br/>原子校验 + 更新]

  F --> G{Lua 返回码}

  G -->|200 SUCCESS| H[返回新 TokenPair] --> H1[拦截器写回 authorization<br/>+ refresh_token Cookie]

  G -->|422 RT不匹配| I[ReadCurrentToken.lua 原子读取<br/>当前凭证]
  I --> J{validVersion == oldVersion？}
  J -->|是| K[返回当前 token<br/>并发刷新自愈] --> H1
  J -->|否| L[拒绝刷新 → null] --> D1

  G -->|413 版本已被顶替| M[拒绝刷新 → null] --> D1
  G -->|421/431 会话不存在| N[拒绝刷新 → null] --> D1
  G -->|未知码| O[拒绝刷新 → null] --> D1

  style A fill:#e3f2fd,stroke:#1976d2,stroke-width:2px,color:#000
  style F fill:#e1f5fe,stroke:#0288d1,stroke-width:2px,color:#000
  style G fill:#fff9c4,stroke:#fbc02d,stroke-width:2px,color:#000
  style H fill:#e8f5e9,stroke:#388e3c,stroke-width:2px,color:#000
  style D1 fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style I fill:#e1f5fe,stroke:#0288d1,stroke-width:2px,color:#000
  style K fill:#e8f5e9,stroke:#388e3c,stroke-width:2px,color:#000
  style L fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style M fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style N fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style O fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
```

## 关键点

- **版本不 bump**：刷新只是"滑动续期"，`newVersion = oldVersion`；登录/换设备才会 `redisIdWorker.nextVersion()` 抬高版本号。
- **版本守卫**：Lua 第 2 步 `orginVersion > version` 返回 413——比当前有效版本还旧的 token 一律拒绝，防止旧会话用已顶替的凭证续期。
- **422 并发自愈**：同一会话并发刷新导致 RT 不匹配时，`ReadCurrentToken.lua` 只在 `validVersion == oldVersion`（仍是同一会话）时返回当前凭证，不会把**新登录**的凭证泄露给旧会话。
- **失败统一 401**：`refreshTokenPair` 返回 `null` 时拦截器置 401 并透传 `X-Auth-Reason: superseded | no-session`，前端据此区分提示文案。
