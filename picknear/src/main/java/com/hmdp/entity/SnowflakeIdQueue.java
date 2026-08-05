package com.hmdp.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 批次ID队列，预先生成批次ID并存储在队列中，确保每次生成ID时都能快速获取一个唯一的批次ID
 * 负载因子，控制批量生成ID的频率，避免过度生成导致内存占用过高
 * 0.75表示当队列中的ID数量达到容量的75%时触发批量生成新ID的逻辑
 * 注意不支持持久化，重启后会丢失数据
 * ID刷新逻辑需在调用方实现，调用方根据返回值决定是否触发异步刷新逻辑，避免过度耦合
 * @author Ntwitm
 */
@NoArgsConstructor
@AllArgsConstructor
/**
 * 分布式雪花ID预生成队列 — 提前生成一批ID放入本地队列，避免高并发下实时生成延迟
 */
@Data
public class SnowflakeIdQueue {
    /**
     * 批次ID队列，预先生成批次ID并存储在队列中，确保每次生成ID时都能快速获取一个唯一的批次ID
     */
    private static final LinkedBlockingQueue<Long> BATCH_ID_QUEUE = new LinkedBlockingQueue<Long>();

    /**
     * 负载因子，控制批量生成ID的频率，避免过度生成导致内存占用过高
      * 0.75表示当队列中的ID数量达到容量的75%时停止批量生成新ID的逻辑
      * 0.25表示当队列中的ID数量达到容量的25%时触发批量生成新ID的逻辑，提供更早的预警
     */
    private static final Double  DEFAULT_MIN_LOAD_FACTOR = 0.25 ;
    private static final Double  DEFAULT_MAX_LOAD_FACTOR = 0.75 ;

    private  int initCapacity = 10000;

    /**
     * 共享锁，控制批量生成ID的线程安全，避免多个线程同时触发批量生成导致过度生成ID
      * 使用AtomicBoolean作为锁标志，确保只有一个线程能够执行批量生成ID的逻辑
      * 当一个线程正在执行批量生成ID时，其他线程无法进入该逻辑，直接返回
     */
    private final   AtomicBoolean isRefreshing = new AtomicBoolean(false);


    /**
     * 这里让其他地方做异步刷新，不在本类里实现刷新功能了，避免过度耦合
     * 返回-1表示需要刷新，调用方根据返回值决定是否触发异步刷新逻辑
     * @return
     * @throws InterruptedException
     */
    public Long take()  {
        if (checkRefresh()) {
            return -1L;
        }
        Long id = null;
        try {
            id = BATCH_ID_QUEUE.take();
        } catch (InterruptedException e) {
            System.out.println("从批次ID队列中获取ID时发生异常: " + e.getMessage());
            throw new RuntimeException(e);
        }
        return id;
    }

    public void put(Long id) throws InterruptedException {
        BATCH_ID_QUEUE.put(id);
    }

    public int size() {
        return BATCH_ID_QUEUE.size();
    }
    /**
     * 根据实际容量决定是否需要批量生成新ID
     * 加锁仅允许一个线程执行批量生成ID的逻辑，避免多个线程同时触发批量生成导致过度生成ID
     */
    public  boolean checkRefresh() {
        if (size() < initCapacity * DEFAULT_MIN_LOAD_FACTOR) {
            if (isRefreshing.compareAndSet(false, true)) {
                // 调用方需手动设置isRefreshing为false，刷新成功手动设置为false，刷新失败也要设置为false，避免死锁
                return true;
            }
        }
        if (size() > initCapacity * DEFAULT_MAX_LOAD_FACTOR) {
            isRefreshing.set(false);
        }
        return false;
    }

    public void finishRefresh() {
        isRefreshing.set(false);
    }
}
