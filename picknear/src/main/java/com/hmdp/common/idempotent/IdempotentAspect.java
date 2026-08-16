package com.hmdp.common.idempotent;

import com.hmdp.exception.BizException;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 幂等切面 — 解析 {@link Idempotent} SpEL key，Redis SETNX 防重放（P4-S5）
 * <p>
 * 语义：
 * <ul>
 *   <li>SETNX 成功 → 首次执行；重复（TTL 窗口内）→ 抛 BizException(429)</li>
 *   <li>业务抛异常 → 删除标记（允许重试）；成功 → 保留至 TTL 过期（窗口防重）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Aspect
@Component
public class IdempotentAspect {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String redisKey = idempotent.prefix() + resolveKey(pjp, idempotent);
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", idempotent.ttl(), TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(ok)) {
            log.warn("幂等拦截重复请求 key={}", redisKey);
            throw new BizException(429, idempotent.message());
        }
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            // 业务失败释放标记，允许重试
            stringRedisTemplate.delete(redisKey);
            throw t;
        }
    }

    private String resolveKey(ProceedingJoinPoint pjp, Idempotent idempotent) {
        String spel = idempotent.key();
        if (spel == null || spel.isBlank()) {
            // 默认：类名:方法名:当前用户ID
            return pjp.getTarget().getClass().getSimpleName() + ":" + pjp.getSignature().getName()
                    + ":" + UserHolder.getUserId();
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object[] args = pjp.getArgs();
        String[] paramNames = signature.getParameterNames();
        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }
        return String.valueOf(PARSER.parseExpression(spel).getValue(context));
    }
}
