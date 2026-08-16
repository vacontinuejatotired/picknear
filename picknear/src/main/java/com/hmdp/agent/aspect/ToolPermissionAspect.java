package com.hmdp.agent.aspect;

import com.hmdp.agent.permission.validator.DataPermissionValidator;
import com.hmdp.agent.permission.validator.PermissionValidatorFactory;
import com.hmdp.agent.permission.annotation.RequiredDataPermission;
import com.hmdp.agent.permission.enums.DataAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * AI 工具方法数据权限校验切面
 * <p>
 * 拦截标注了 {@link RequiredDataPermission @RequiredDataPermission} 的 {@code @Tool} 方法，
 * 在方法执行前校验当前用户是否有权操作目标资源。
 * </p>
 * <h3>设计说明</h3>
 * <ul>
 *   <li>当前用户 ID 从 {@link ToolContext} 中提取（兼容异步线程）</li>
 *   <li>目标资源 ID 自动扫描方法参数中的 Long/Integer 参数</li>
 *   <li>校验逻辑委托给 {@link DataPermissionValidator} 策略接口</li>
 *   <li>新增资源只需添加 Validator 实现类，切面和工厂零修改</li>
 *   <li>拒绝语义与 guard BLOCK 统一（M-6）：失败返回 {@code {"error":"..."}} JSON，
 *       LLM 按工具错误自纠/转述，而非把裸文本当业务结果</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolPermissionAspect {

    private final PermissionValidatorFactory validatorFactory;

    /**
     * 切点：所有标注了 {@link RequiredDataPermission} 的方法
     */
    @Pointcut("@annotation(com.hmdp.agent.permission.annotation.RequiredDataPermission)")
    public void permissionCheckPointcut() {
    }

    /**
     * 环绕通知：提取用户身份和目标资源 ID → 路由到对应校验器 → 放行或拒绝
     */
    @Around("permissionCheckPointcut()")
    public Object requiredDataPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取注解信息
        java.lang.reflect.Method method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        RequiredDataPermission annotation = method.getAnnotation(RequiredDataPermission.class);
        String resource = annotation.resource();
        DataAction action = annotation.action();
        log.debug("权限校验开始 [resource={}, action={}, method={}]", resource, action, method.getName());
        // 2. 从方法参数中提取 ToolContext 和目标资源 ID
        ToolContext toolContext = null;
        Long targetId = null;
        Object[] args = joinPoint.getArgs();

        for (Object arg : args) {
            if (arg instanceof ToolContext ctx) {
                toolContext = ctx;
            } else if (targetId == null && (arg instanceof Long || arg instanceof Integer)) {
                targetId = ((Number) arg).longValue();
            }
        }

        // 3. 校验 ToolContext 和 userId
        String userIdError = extractAndValidateUserId(toolContext);
        if (userIdError != null) {
            return userIdError;
        }
        Long currentUserId = userIdFromContext(toolContext);

        // 4. 校验目标资源 ID
        //    - CREATE 操作无目标 ID（新资源尚未创建），跳过校验器直接放行
        //    - READ/UPDATE/DELETE 无目标 ID（如"查自己全部博客"自限查询），同样无需资源归属校验
        if (targetId == null) {
            log.debug("权限放行：无目标资源 ID [userId={}, resource={}, action={}, method={}]",
                    currentUserId, resource, action, method.getName());
            return joinPoint.proceed();
        }

        // 5. 通过工厂获取校验器并执行校验
        DataPermissionValidator validator = validatorFactory.getValidator(resource);
        if (validator == null) {
            log.warn("权限校验失败：未配置资源 {} 的校验规则 [userId={}, targetId={}, action={}]",
                    resource, currentUserId, targetId, action);
            return errorJson("系统未配置该资源的权限校验规则");
        }

        boolean hasPermission = validator.validate(currentUserId, targetId, action);
        if (!hasPermission) {
            log.warn("权限校验失败：无权限 [userId={}, resource={}, targetId={}, action={}]",
                    currentUserId, resource, targetId, action);
            return errorJson("无权操作该" + validator.getResourceLabel());
        }

        // 6. 校验通过 — 记录审计日志并执行原方法
        log.debug("权限校验通过 [userId={}, resource={}, targetId={}, action={}, method={}]",
                currentUserId, resource, targetId, action, method.getName());
        return joinPoint.proceed();
    }

    /**
     * 从 ToolContext 中提取并校验当前用户 ID
     *
     * @param toolContext 工具上下文
     * @return null 表示校验通过；非 null 为错误 JSON（与 guard BLOCK 失败语义一致）
     */
    private String extractAndValidateUserId(ToolContext toolContext) {
        if (toolContext == null) {
            log.warn("权限校验失败：方法缺少 ToolContext 参数，无法获取用户身份");
            return errorJson("身份验证失败，请重新登录");
        }
        Long currentUserId = userIdFromContext(toolContext);
        if (currentUserId == null) {
            log.warn("权限校验失败：ToolContext 中未找到 userId");
            return errorJson("身份验证失败，请重新登录");
        }
        return null;
    }

    /** 从 ToolContext 安全提取 userId（非 Long 类型不抛 ClassCastException，视为缺失） */
    private static Long userIdFromContext(ToolContext toolContext) {
        if (toolContext.getContext() != null) {
            Object uid = toolContext.getContext().get("userId");
            if (uid instanceof Long l) {
                return l;
            }
        }
        return null;
    }

    /** 与 {@code GuardedToolCallback} BLOCK 分支一致的错误 JSON 结构（M-6 语义统一） */
    private static String errorJson(String msg) {
        return "{\"error\":\"" + msg + "\"}";
    }
}
