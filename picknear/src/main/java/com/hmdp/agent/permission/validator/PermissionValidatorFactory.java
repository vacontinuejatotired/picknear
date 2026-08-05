package com.hmdp.agent.permission.validator;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据权限校验器工厂
 * <p>
 * Spring 启动时自动收集所有 {@link DataPermissionValidator} Bean，
 * 以 {@link DataPermissionValidator#getResourceType()} 为键注册到内部 Map。
 * AOP 切面通过 {@link #getValidator(String)} 获取对应校验器，无需硬编码。
 * </p>
 * <pre>{@code
 * // 在切面中使用
 * DataPermissionValidator validator = factory.getValidator(resource);
 * if (validator != null) {
 *     boolean pass = validator.validate(userId, targetId, action);
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionValidatorFactory {

    private final List<DataPermissionValidator> validators;

    private final Map<String, DataPermissionValidator> validatorMap = new HashMap<>();

    /**
     * 初始化：将所有校验器注册到 Map 中
     * <p>键 = validator.getResourceType()，值 = validator 实例</p>
     */
    @PostConstruct
    public void init() {
        for (DataPermissionValidator validator : validators) {
            String type = validator.getResourceType();
            if (validatorMap.containsKey(type)) {
                log.warn("权限校验器资源类型重复注册 [resource={}, existing={}, new={}]",
                        type, validatorMap.get(type).getClass().getSimpleName(),
                        validator.getClass().getSimpleName());
            }
            validatorMap.put(type, validator);
            log.info("注册权限校验器 [resource={}, validator={}]",
                    type, validator.getClass().getSimpleName());
        }
        log.info("权限校验器工厂初始化完成，共注册 {} 个校验器", validatorMap.size());
    }

    /**
     * 根据资源类型获取对应的校验器
     *
     * @param resource 资源类型（如 "blog"、"user"）
     * @return 校验器实例，若未注册则返回 null
     */
    public DataPermissionValidator getValidator(String resource) {
        return validatorMap.get(resource);
    }

    /**
     * 获取当前已注册的所有资源类型列表
     *
     * @return 资源类型集合
     */
    public java.util.Set<String> getRegisteredTypes() {
        return validatorMap.keySet();
    }
}
