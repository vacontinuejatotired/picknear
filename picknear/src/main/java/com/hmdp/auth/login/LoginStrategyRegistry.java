package com.hmdp.auth.login;

import com.hmdp.auth.dto.LoginFormDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 登录策略注册器 — 收集所有 {@link LoginStrategy}，按 supports 自判定路由
 * <p>
 * 对齐 guard 策略风格（ToolGuardManager 注入 List 自动收集）：
 * 新增登录方式只需新增策略实现类，注册器零改动。
 * </p>
 */
@Slf4j
@Component
public class LoginStrategyRegistry {

    private final List<LoginStrategy> strategies;

    public LoginStrategyRegistry(List<LoginStrategy> strategies) {
        this.strategies = strategies;
        log.info("LoginStrategyRegistry 初始化，已注册 {} 个策略: {}",
                strategies.size(),
                strategies.stream().map(LoginStrategy::strategyName).toList());
    }

    /** 按表单内容解析适用的登录策略，无命中抛异常 */
    public LoginStrategy resolve(LoginFormDTO form) {
        return strategies.stream()
                .filter(s -> s.supports(form))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的登录方式"));
    }
}
