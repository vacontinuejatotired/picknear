package com.hmdp.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等注解 — Redis SETNX 防重放（common 域收敛，P4-S5）
 * <p>
 * 用法（SpEL 表达式取业务键，可用 {@code T(com.hmdp.utils.UserHolder).getUserId()}
 * 等静态引用）：
 * <pre>
 * &#64;Idempotent(key = "#voucherId + ':' + T(com.hmdp.utils.UserHolder).getUserId()", ttl = 10)
 * public Result saveOrder(Long voucherId) { ... }
 * </pre>
 * 语义：TTL 窗口内同 key 重复请求抛 {@code BizException(429, message)}；
 * 业务执行抛异常时自动释放标记（允许重试）。
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 业务键 SpEL 表达式（空则默认：类名:方法名:当前用户ID） */
    String key() default "";

    /** 幂等窗口（秒），窗口内同 key 拒绝重复 */
    long ttl() default 10;

    /** Redis key 前缀 */
    String prefix() default "idem:";

    /** 重复请求提示文案 */
    String message() default "请勿重复提交";
}
