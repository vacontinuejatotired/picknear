package com.hmdp.auth.login;

import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.TokenPair;

/**
 * 登录策略 — 对一种登录方式进行完整处理（P2-S5，对齐 guard 策略风格）
 * <p>
 * <strong>职责边界：</strong>
 * <ul>
 *   <li>supports(form)：自判定是否适用（密码登录 = password 非空；验证码登录 = password 为空）</li>
 *   <li>login(form)：完成该方式的完整登录链路（校验 → 查/建账号 → 生成双 Token）</li>
 *   <li>所有实现类标注 {@code @Component}，{@link LoginStrategyRegistry} 启动时自动收集</li>
 * </ul>
 * 新增登录方式（如 OAuth2）= 新增一个策略类，零改动既有代码（开闭原则）。
 * </p>
 *
 * @see LoginStrategyRegistry
 */
public interface LoginStrategy {

    /** 策略唯一标识（日志/监控用） */
    default String strategyName() {
        return getClass().getSimpleName();
    }

    /** 该策略是否适用于当前登录表单 */
    boolean supports(LoginFormDTO form);

    /** 执行登录，返回双 Token */
    TokenPair login(LoginFormDTO form);
}
