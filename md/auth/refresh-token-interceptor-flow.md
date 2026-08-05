# RefreshTokenInterceptor 拦截流程

> 2026-08-05 更新：刷新统一委托 `AuthService.refreshTokenPair`；401 时透传
> `X-Auth-Reason: superseded | no-session`（区分"账号已在其他设备登录"与"登录已过期"）；
> 刷新锁被占用时跳过刷新放行（`X-Token-Refresh: skipped`）。

```mermaid
flowchart TD
  A[收到请求] --> B{有 authorization 头？}
  B -->|否| C[401 token is null] --> END

  B -->|是| D[取 token<br/>strip Bearer]
  D --> E[从 httpOnly Cookie 取 refresh_token]
  E --> F[AuthService.validateAccessToken]

  F --> G{校验结果}
  G -->|JWT无效 且无需刷新| H[401 + X-Auth-Reason<br/>isSuperseded? superseded : no-session] --> END
  G -->|userId 为空| H2[401] --> END
  G -->|用户信息加载失败| H3[401] --> END
  G -->|有效 且无需刷新| I[放行] --> END

  G -->|需要刷新| J[加分布式锁<br/>lock:refresh:userId, 3s]
  J --> K{抢到锁？}
  K -->|否| L[X-Token-Refresh: skipped<br/>放行，避免旧值覆盖新 token] --> END

  K -->|是| M{version 为空？}
  M -->|是| H2
  M -->|否| N[refreshTokenPair<br/>Lua 原子刷新]

  N --> O{返回 null？}
  O -->|否| P[写回 authorization<br/>+ refresh_token Cookie<br/>X-Token-Refresh: ok] --> I
  O -->|是| Q[401 + X-Auth-Reason<br/>superseded / no-session] --> END

  END([结束])

  style A fill:#e3f2fd,stroke:#1976d2,stroke-width:2px,color:#000
  style F fill:#e1f5fe,stroke:#0288d1,stroke-width:2px,color:#000
  style G fill:#fff9c4,stroke:#fbc02d,stroke-width:2px,color:#000
  style H fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style H2 fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style H3 fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style I fill:#e8f5e9,stroke:#388e3c,stroke-width:2px,color:#000
  style J fill:#fff3e0,stroke:#f57c00,stroke-width:2px,color:#000
  style L fill:#fff3e0,stroke:#f57c00,stroke-width:2px,color:#000
  style N fill:#e1f5fe,stroke:#0288d1,stroke-width:2px,color:#000
  style P fill:#e8f5e9,stroke:#388e3c,stroke-width:2px,color:#000
  style Q fill:#ffebee,stroke:#d32f2f,stroke-width:2px,color:#000
  style END fill:#f5f5f5,stroke:#9e9e9e,stroke-width:2px,color:#000
```

## 关键点

- **401 语义统一**：`X-Auth-Reason` 只在两条路径透传——① `validateAccessToken` 返回"无效且无需刷新"（含未过期被顶替）；② `refreshTokenPair` 返回 `null`。`isSuperseded` 读 Redis `validVersion > token.version` 判定。
- **刷新锁**：同一用户并发只允许一个刷新请求执行（3s 锁），抢锁失败者不阻塞、直接放行（前端已有单飞重试，刷新由并发请求完成）。
- **Refresh Token 走 Cookie**：httpOnly `refresh_token` Cookie，JS 不可读；刷新成功由后端 Set-Cookie 覆盖。
- **前端配合**：`request.ts` 收到 401 后单飞重试（若另一请求已刷新则用新 token 重发）→ 单飞登出（读 `X-Auth-Reason`，`superseded` 提示"账号已在其他设备登录"）。
