-- KEYS[1]: tokenKey = "login:token:" .. userId
-- KEYS[2]: versionKey = "token:version:" .. userId
-- KEYS[3]: refreshKey = "refresh:user:" .. userId
-- KEYS[4]? newVersionKey (最新version号的 key，过期时间和 refreshKey 一致)
-- ARGV[1]: oldToken (请求携带的 token)
-- ARGV[2]: newToken (新生成的 token)
-- ARGV[3]: expireSeconds (新 token 的过期秒数)
-- ARGV[4]: oldVersion (从 token 中解析的旧版本号)
-- ARGV[5]: clientRefreshToken (请求携带的 refreshToken)

local tokenKey = KEYS[1]
local versionKey = KEYS[2]
local refreshKey = KEYS[3]
local newVersionKey = KEYS[4]
local userInfoKey =  KEYS[5]
local oldToken = ARGV[1]
local newToken = ARGV[2]
local tokenExpireSeconds = tonumber(ARGV[3])
local oldVersion = tonumber(ARGV[4])
local clientRefreshToken = ARGV[5]
local newVersionExpireSeconds=tonumber(ARGV[6])
local refreshExpireSeconds = tonumber(ARGV[7])
local userinfoExpireSeconds = tonumber(ARGV[8])
-- 1. 验证 refreshToken
local storedRefresh = redis.call('GET', refreshKey)
local userInfoExists = redis.call('exists', userInfoKey)

--if userInfo == '' then
--    return 442  --USERINFO_CACHE_EMPTY
--end

if storedRefresh == nil then
    return 421  -- REFRESH_TOKEN_NOT_FOUND
end

if storedRefresh ~= clientRefreshToken then
    return 422  -- REFRESH_TOKEN_MISMATCH
end

-- 2. 验证版本号
local validVersion = redis.call('GET', versionKey)
if validVersion == nil then
    return 411  -- VERSION_KEY_NOT_FOUND
end

if tonumber(validVersion) > oldVersion then
    return 412  -- TOKEN_VERSION_EXPIRED
end

-- 3. 验证 token 是否存在
local exists = redis.call('EXISTS', tokenKey)
if exists == 0 then
    return 401  -- TOKEN_KEY_NOT_FOUND
end

-- 4. 验证 token 是否匹配
local storedToken = redis.call('GET', tokenKey)
if storedToken ~= oldToken then
    return 402  -- TOKEN_MISMATCH
end

if userInfoExists == 0 then
    --用户信息未缓存，返回状态码继续查mysql拿用户信息
    redis.call('SET', tokenKey, newToken, 'EX', tokenExpireSeconds)
    redis.call('EXPIRE', newVersionKey, newVersionExpireSeconds)
    redis.call('EXPIRE', refreshKey, refreshExpireSeconds)
    redis.call('EXPIRE', versionKey, refreshExpireSeconds)
    return 441
end
-- 5. 更新 token
redis.call('SET', tokenKey, newToken, 'EX', tokenExpireSeconds)
redis.call('EXPIRE',newVersionKey,newVersionExpireSeconds)
redis.call('EXPIRE', refreshKey, refreshExpireSeconds)
redis.call('EXPIRE',versionKey,refreshExpireSeconds)
redis.call('EXPIRE',userInfoKey,userinfoExpireSeconds)
-- 6. 返回成功
return 200  -- SUCCESS