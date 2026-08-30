package com.hmdp.agent.permission.validator.impl;

import com.hmdp.agent.permission.enums.DataAction;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.mapper.BlogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BlogPermissionValidator — 博客权限校验器测试。
 * <p>
 * 覆盖归属校验、不存在、空 ID、类型兼容。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class BlogPermissionValidatorTest {

    @Mock
    private BlogMapper blogMapper;

    private BlogPermissionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BlogPermissionValidator(blogMapper);
    }

    @Test
    void should_return_true_when_owner() {
        Blog blog = new Blog();
        blog.setUserId(1L);
        when(blogMapper.selectById(1L)).thenReturn(blog);

        boolean result = validator.validate(1L, 1L, DataAction.READ);

        assertThat(result).as("本人博客应放行").isTrue();
    }

    @Test
    void should_return_false_when_not_owner() {
        Blog blog = new Blog();
        blog.setUserId(2L);
        when(blogMapper.selectById(2L)).thenReturn(blog);

        boolean result = validator.validate(1L, 2L, DataAction.READ);

        assertThat(result).as("他人博客应拒绝").isFalse();
    }

    @Test
    void should_return_false_when_blog_not_found() {
        when(blogMapper.selectById(999L)).thenReturn(null);

        boolean result = validator.validate(1L, 999L, DataAction.READ);

        assertThat(result).as("不存在的博客应拒绝").isFalse();
    }

    @Test
    void should_return_false_when_target_id_null() {
        boolean result = validator.validate(1L, null, DataAction.READ);

        assertThat(result).as("null targetId 应拒绝").isFalse();
    }

    @Test
    void should_return_false_when_target_id_is_string() {
        boolean result = validator.validate(1L, "abc", DataAction.READ);

        assertThat(result).as("String 类型 targetId 应拒绝").isFalse();
    }

    @Test
    void should_accept_integer_target_id() {
        Blog blog = new Blog();
        blog.setUserId(1L);
        when(blogMapper.selectById(1L)).thenReturn(blog);

        boolean result = validator.validate(1L, Integer.valueOf(1), DataAction.READ);

        assertThat(result).as("Integer 类型 targetId 应正常处理").isTrue();
    }
}
