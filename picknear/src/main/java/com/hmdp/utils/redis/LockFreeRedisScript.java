package com.hmdp.utils.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.DigestUtils;


/**
 * 无锁 RedisScript 实现 — 重写 getSha1() 预计算 SHA1，避免高并发下 synchronized 锁竞争
 */
public class LockFreeRedisScript extends DefaultRedisScript {
    private final String cacheSha1;

    public LockFreeRedisScript(String scriptText, Class<?> resultType) {
        setScriptText(scriptText);
        setResultType(resultType);
        this.cacheSha1 = DigestUtils.md5DigestAsHex(scriptText.getBytes());
    }
    @Override
    public String getSha1() {
        return cacheSha1;
    }
    @Override
    public String getScriptAsString() {
        return super.getScriptAsString();
    }
}
