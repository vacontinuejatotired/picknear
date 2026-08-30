package com.hmdp.agent.permission.validator.impl;

import com.hmdp.agent.permission.enums.DataAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserPermissionValidator — 用户权限校验器测试。
 * <p>
 * 纯逻辑，无外部依赖。
 */
class UserPermissionValidatorTest {

    private final UserPermissionValidator validator = new UserPermissionValidator();

    @Test
    void should_return_true_when_self() {
        boolean result = validator.validate(1L, 1L, DataAction.READ);

        assertThat(result).as("操作自己的账户应放行").isTrue();
    }

    @Test
    void should_return_false_when_other() {
        boolean result = validator.validate(1L, 2L, DataAction.READ);

        assertThat(result).as("操作他人账户应拒绝").isFalse();
    }

    @Test
    void should_return_false_when_target_null() {
        boolean result = validator.validate(1L, null, DataAction.READ);

        assertThat(result).as("null targetId 应拒绝").isFalse();
    }
}
