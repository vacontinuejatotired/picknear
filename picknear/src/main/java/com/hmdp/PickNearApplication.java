package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({"com.hmdp.mapper", "com.hmdp.agent.mapper"})
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true,proxyTargetClass = true)
@EnableScheduling
public class PickNearApplication {
    public static void main(String[] args) {
        SpringApplication.run(PickNearApplication.class, args);
    }
}
