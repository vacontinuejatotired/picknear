package com.hmdp.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

/**
 * 方法耗时记录切面 — 通过 AOP 拦截 @RecordTime 注解，打印执行耗时
 */
@Aspect
@Component
@Slf4j
public class AutoRecordTime {

    @Pointcut("@annotation(com.hmdp.annotation.RecordTime)")
    public void pointcut() {}

    @Around("pointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 创建StopWatch
        StopWatch stopWatch = new StopWatch(joinPoint.getSignature().getName());

        try {
            stopWatch.start("总执行时间");

            // 执行原方法，内部所有方法调用都会被包含
            Object result = joinPoint.proceed();

            stopWatch.stop();

            // 记录总耗时
            log.info("【{}#{}】总耗时: {} ms",
                    joinPoint.getTarget().getClass().getSimpleName(),
                    joinPoint.getSignature().getName(),
                    stopWatch.getTotalTimeMillis());

            // 如果大于1000ms，打印详细时间
            if (stopWatch.getTotalTimeMillis() > 100) {
                log.info("详细耗时统计:\n{}", stopWatch.prettyPrint());
            }

            return result;

        } catch (Throwable t) {
            if (stopWatch.isRunning()) {
                stopWatch.stop();
            }
            log.error("【{}#{}】执行异常，耗时: {} ms",
                    joinPoint.getTarget().getClass().getSimpleName(),
                    joinPoint.getSignature().getName(),
                    stopWatch.getTotalTimeMillis(), t);
            throw t;
        }
    }
}
