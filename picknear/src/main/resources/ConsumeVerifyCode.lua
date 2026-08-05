---
--- 原子消费验证码：GET + DEL，防止同一验证码重复使用
--- KEYS[1] = login:code:{phone}
--- RETURN: code(string) 或 nil（已被消费或不存在）
---
local code = redis.call('GET', KEYS[1])
if code then
    redis.call('DEL', KEYS[1])
end
return code
