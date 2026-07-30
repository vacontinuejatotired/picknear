package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 测试/工具服务接口 — 秒杀重置、Token批量生成、雪花ID校验、MQ批量下单
 */
public interface ITestService {

    Result restart(Long num,Long voucherId);

    Result generateTestToken(Long num,String fileName);

    Result checkSnowFlake(int num);

    Result testSaveOrder(Long voucherId, Long orderNum);
}
