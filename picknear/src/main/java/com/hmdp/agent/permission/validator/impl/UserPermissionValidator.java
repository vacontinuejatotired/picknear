package com.hmdp.agent.permission.validator.impl;

import com.hmdp.agent.permission.enums.DataAction;
import com.hmdp.agent.permission.validator.DataPermissionValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户资源权限校验器
 * <p>
 * 校验规则：用户只能操作自己的账户，即 {@code targetId == userId}。
 * </p>
 * <ul>
 *   <li>操作他人账户 → 无权限（false）</li>
 *   <li>操作自己账户 → 有权限（true）</li>
 * </ul>
 */
@Slf4j
@Component
public class UserPermissionValidator implements DataPermissionValidator {

    @Override
    public String getResourceType() {
        return "user";
    }

    @Override
    public String getResourceLabel() {
        return "用户";
    }

    @Override
    public boolean validate(Long userId, Object targetId, DataAction action) {
        if (targetId == null) {
            log.warn("用户权限校验失败：targetId 为空 [userId={}]", userId);
            return false;
        }

        Long targetUserId;
        if (targetId instanceof Number) {
            targetUserId = ((Number) targetId).longValue();
        } else {
            log.warn("用户权限校验失败：targetId 类型不支持 [type={}]", targetId.getClass());
            return false;
        }

        boolean hasPermission = userId.equals(targetUserId);
        if (!hasPermission) {
            log.warn("用户权限校验失败：越权操作他人账户 [userId={}, targetUserId={}, action={}]",
                    userId, targetUserId, action);
        }
        return hasPermission;
    }
}
