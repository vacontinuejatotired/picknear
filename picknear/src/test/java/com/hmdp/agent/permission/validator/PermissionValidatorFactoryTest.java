package com.hmdp.agent.permission.validator;

import com.hmdp.agent.permission.enums.DataAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PermissionValidatorFactory — 权限校验器工厂测试。
 * <p>
 * 覆盖注册、查找、重复类型、列出类型。
 */
class PermissionValidatorFactoryTest {

    private final DataPermissionValidator blogValidator = new DataPermissionValidator() {
        @Override
        public String getResourceType() { return "blog"; }
        @Override
        public boolean validate(Long userId, Object targetId, DataAction action) { return true; }
    };

    private final DataPermissionValidator userValidator = new DataPermissionValidator() {
        @Override
        public String getResourceType() { return "user"; }
        @Override
        public boolean validate(Long userId, Object targetId, DataAction action) { return true; }
    };

    private final DataPermissionValidator blogValidator2 = new DataPermissionValidator() {
        @Override
        public String getResourceType() { return "blog"; }
        @Override
        public boolean validate(Long userId, Object targetId, DataAction action) { return false; }
    };

    @Test
    void should_register_validators_by_type() {
        PermissionValidatorFactory factory = new PermissionValidatorFactory(List.of(blogValidator, userValidator));
        factory.init();

        assertThat(factory.getValidator("blog")).as("blog 校验器应注册").isSameAs(blogValidator);
        assertThat(factory.getValidator("user")).as("user 校验器应注册").isSameAs(userValidator);
    }

    @Test
    void should_return_null_for_unregistered_type() {
        PermissionValidatorFactory factory = new PermissionValidatorFactory(List.of(blogValidator));
        factory.init();

        assertThat(factory.getValidator("order")).as("未注册类型应返回 null").isNull();
    }

    @Test
    void should_log_warning_on_duplicate_type() {
        // duplicate blog type: second one overwrites first
        PermissionValidatorFactory factory = new PermissionValidatorFactory(List.of(blogValidator, blogValidator2));
        factory.init();

        // 后注册的覆盖前面的
        assertThat(factory.getValidator("blog")).as("重复注册应被覆盖").isSameAs(blogValidator2);
    }

    @Test
    void should_list_all_registered_types() {
        PermissionValidatorFactory factory = new PermissionValidatorFactory(List.of(blogValidator, userValidator));
        factory.init();

        assertThat(factory.getRegisteredTypes()).as("应包含所有注册类型")
                .containsExactlyInAnyOrder("blog", "user");
    }
}
