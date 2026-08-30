package com.hmdp.agent.aspect;

import com.hmdp.agent.permission.annotation.RequiredDataPermission;
import com.hmdp.agent.permission.enums.DataAction;
import com.hmdp.agent.permission.validator.DataPermissionValidator;
import com.hmdp.agent.permission.validator.PermissionValidatorFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ToolPermissionAspect — 数据权限校验 AOP 切面测试。
 * <p>
 * 覆盖 CREATE 放行、READ 放行、有权限通过、无权限拒绝、
 * 缺少 ToolContext、缺少校验器、userId 正确提取。
 */
@ExtendWith(MockitoExtension.class)
class ToolPermissionAspectTest {

    @Mock private PermissionValidatorFactory validatorFactory;
    @Mock private DataPermissionValidator blogValidator;
    @InjectMocks private ToolPermissionAspect aspect;

    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private MethodSignature signature;
    @Mock private Method method;
    @Mock private RequiredDataPermission mockAnnotation;
    @Mock private ToolContext toolContext;

    @BeforeEach
    void setUp() throws Throwable {
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(signature.getMethod()).thenReturn(method);
        lenient().when(method.getAnnotation(RequiredDataPermission.class)).thenReturn(mockAnnotation);
        lenient().when(mockAnnotation.resource()).thenReturn("blog");
        lenient().when(mockAnnotation.action()).thenReturn(DataAction.READ);
        lenient().when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        lenient().doReturn("success").when(joinPoint).proceed();
    }

    @Test
    void should_proceed_when_create_and_no_target_id() throws Throwable {
        when(mockAnnotation.action()).thenReturn(DataAction.CREATE);
        when(joinPoint.getArgs()).thenReturn(new Object[]{toolContext});

        Object result = aspect.requiredDataPermission(joinPoint);

        assertThat(result).as("CREATE + 无 targetId 应放行").isEqualTo("success");
        verify(joinPoint).proceed();
    }

    @Test
    void should_proceed_when_read_and_no_target_id() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{toolContext});

        Object result = aspect.requiredDataPermission(joinPoint);

        assertThat(result).as("READ + 无 targetId 应放行").isEqualTo("success");
        verify(joinPoint).proceed();
    }

    @Test
    void should_proceed_when_valid_target_id_and_has_permission() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{toolContext, 1L});
        when(validatorFactory.getValidator("blog")).thenReturn(blogValidator);
        when(blogValidator.validate(1L, 1L, DataAction.READ)).thenReturn(true);

        Object result = aspect.requiredDataPermission(joinPoint);

        assertThat(result).as("有权限时应放行").isEqualTo("success");
        verify(joinPoint).proceed();
    }

    @Test
    void should_return_error_when_no_permission() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{toolContext, 2L});
        when(validatorFactory.getValidator("blog")).thenReturn(blogValidator);
        when(blogValidator.validate(1L, 2L, DataAction.READ)).thenReturn(false);

        Object result = aspect.requiredDataPermission(joinPoint);

        assertThat(result).as("无权限应返回错误信息").asString().contains("无权操作");
        verify(joinPoint, never()).proceed();
    }

    @Test
    void should_return_error_when_no_tool_context() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L});

        Object result = aspect.requiredDataPermission(joinPoint);

        assertThat(result).as("缺少 ToolContext 应返回身份验证失败").asString().contains("身份验证失败");
        verify(joinPoint, never()).proceed();
    }

    @Test
    void should_return_error_when_no_validator() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{toolContext, 1L});
        when(validatorFactory.getValidator("blog")).thenReturn(null);

        Object result = aspect.requiredDataPermission(joinPoint);

        assertThat(result).as("缺少校验器应返回系统未配置").asString().contains("系统未配置");
        verify(joinPoint, never()).proceed();
    }

    @Test
    void should_extract_user_id_from_tool_context() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{toolContext, 1L});
        when(validatorFactory.getValidator("blog")).thenReturn(blogValidator);
        when(blogValidator.validate(eq(1L), eq(1L), eq(DataAction.READ))).thenReturn(true);

        aspect.requiredDataPermission(joinPoint);

        // 验证 userId 从 ToolContext 提取并传入校验器
        verify(blogValidator).validate(eq(1L), eq(1L), any());
    }
}
