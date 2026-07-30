package com.hmdp.utils.redis;


/**
 * 分布式锁接口 — 定义 tryLock / unlock 方法
 */
public interface ILock {
    /**
     * 拿到锁
     * @param timeoutSec 持有时间
     * @return
     */
    boolean tryLock(long timeoutSec);

    void unlock();
}
