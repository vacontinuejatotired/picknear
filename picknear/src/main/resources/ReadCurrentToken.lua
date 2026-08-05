---
--- 并发刷新容错：RefreshExpiredToken.lua 返回 422（RT 不匹配）时，
--- 原子读取当前 token 对，仅在确认是"同一会话的并发刷新"时返回。
--- Created by Ntwitm.
--- DateTime: 2026/8/5
---
-- 为什么需要本脚本：
--   1. 非原子读（Java 分次 GET）存在竞态窗口，高 RTT Redis 下放大；
--   2. 必须带版本守卫：若 validVersion 已被新登录顶高，当前 AT/RT 属于
--      新会话，向旧会话返回即为凭证泄露。RefreshExpiredToken.lua 的
--      版本检查（413）发生在 RT 校验（422）之前，但 422 返回后、本脚本
--      执行前仍可能有新登录落地，故此处需再守一道。
--
-- KEYS[1]: tokenKey        = "login:token:access:" .. userId
-- KEYS[2]: refreshKey      = "login:token:refresh:" .. userId
-- KEYS[3]: validVersionKey = "token:version:valid:" .. userId
--
-- ARGV[1]: oldAccessToken（请求携带的旧 AT，用于判断是否已被并发刷新轮换）
-- ARGV[2]: oldVersion（来自旧 token claims 的版本号）
--
-- 返回：
--   {}                        = 无并发刷新 / 会话已被新登录顶替 → Java 拒绝
--   {currentAT, currentRT}    = 同一会话的并发刷新已发生 → 返回当前凭证自愈

local currentAT = redis.call('GET', KEYS[1])
if currentAT == nil or currentAT == ARGV[1] then
    -- AT 未变或缺失 → 没有发生轮换，不是并发刷新场景
    return {}
end

-- 版本守卫：validVersion 必须仍等于旧版本才属于同一会话。
-- 刷新不 bump 版本，所以合法并发刷新时两者相等；一旦被新登录顶高则拒绝。
local validVersion = redis.call('GET', KEYS[3])
if validVersion == nil or tonumber(validVersion) ~= tonumber(ARGV[2]) then
    return {}
end

local currentRT = redis.call('GET', KEYS[2])
if currentRT == nil then
    return {}
end

return { currentAT, currentRT }
