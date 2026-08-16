package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

// @EnableScheduling 由 SchedulingThreadConfig 统一开启（避免重复声明）
@MapperScan({"com.hmdp.mapper", "com.hmdp.agent.mapper", "com.hmdp.shop.mapper", "com.hmdp.voucher.mapper", "com.hmdp.user.mapper"})
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true,proxyTargetClass = true)
public class PickNearApplication {
    public static void main(String[] args) {
        SpringApplication.run(PickNearApplication.class, args);
    }
}
