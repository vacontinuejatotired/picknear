# Permission 权限层测试（阶段二 · 按需）

> 仅当 `permission/` 或 `aspect/` 包下代码变更时生成。

---

## ⛔ Permission 专属禁止

- **禁止在 `BlogPermissionValidator` 测试中真实查询 DB**——`IBlogService` 必须 Mock
- **禁止在 `ToolPermissionAspect` 测试中启动完整 AOP 容器**——直接调用切面方法或 `@InjectMocks` 手写 JointPoint Mock
- **禁止真实查询 `blogService.getById(id)` 测 BlogPermissionValidator**——Mock 返回 Blog 对象

---

## ToolPermissionAspect（AOP 切面）

### Mock JointPoint

```java
@ExtendWith(MockitoExtension.class)
class ToolPermissionAspectTest {
    @Mock PermissionValidatorFactory validatorFactory;
    @Mock DataPermissionValidator blogValidator;
    @InjectMocks ToolPermissionAspect aspect;

    @Mock ProceedingJoinPoint joinPoint;
    @Mock MethodSignature signature;
    @Mock Method method;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(signature.getMethod()).thenReturn(method);
    }
}
```

### 必须生成的测试

| # | 方法 | action | 参数 | Validator | 断言 |
|---|------|--------|------|-----------|------|
| 1 | `should_proceed_when_create_and_no_target_id` | CREATE | 只有 ToolContext，无 Long/Integer | `verify(joinPoint).proceed()` |
| 2 | `should_proceed_when_read_and_no_target_id` | READ | 同上 | proceed（自限查询，无 targetId） |
| 3 | `should_proceed_when_valid_target_id_and_has_permission` | READ | ToolContext + blogId=1L | validator.validate(1L, 1L, READ)→true → proceed |
| 4 | `should_return_error_when_no_permission` | READ | ToolContext + blogId=2L | validate→false → 返回 "❌ 无权操作该博客" |
| 5 | `should_return_error_when_no_tool_context` | — | 只有 blogId，无 ToolContext | 返回 "❌ 身份验证失败" |
| 6 | `should_return_error_when_no_validator` | READ | blogId=1L | validatorFactory→null → 返回 "❌ 系统未配置" |
| 7 | `should_extract_user_id_from_tool_context` | — | ToolContext 含 userId=1L | validate 被调用时 userId=1L |

```java
// 关键：模拟 @RequiredDataPermission 注解
@BeforeEach
void setUp() throws Exception {
    when(method.getAnnotation(RequiredDataPermission.class))
        .thenReturn(mockAnnotation);
    when(mockAnnotation.resource()).thenReturn("blog");
    when(mockAnnotation.action()).thenReturn(DataAction.READ);
}
```

---

## PermissionValidatorFactory

| # | 方法 | 条件 | 断言 |
|---|------|------|------|
| 1 | `should_register_validators_by_type` | validators=[blogValidator, userValidator] | getValidator("blog")=blogValidator |
| 2 | `should_return_null_for_unregistered_type` | — | getValidator("order")=null |
| 3 | `should_log_warning_on_duplicate_type` | 2 个 validator 都返回 "blog" | 日志警告（验证 log 调用，可选） |
| 4 | `should_list_all_registered_types` | 注册 blog+user | getRegisteredTypes()={"blog","user"} |

---

## BlogPermissionValidator

| # | 方法 | userId | targetId | Mock blogService | 断言 |
|---|------|--------|---------|-----------------|------|
| 1 | `should_return_true_when_owner` | 1L | 1L | blog.userId=1L | true |
| 2 | `should_return_false_when_not_owner` | 1L | 2L | blog.userId=2L | false |
| 3 | `should_return_false_when_blog_not_found` | 1L | 999L | getById→null | false |
| 4 | `should_return_false_when_target_id_null` | 1L | null | — | false |
| 5 | `should_return_false_when_target_id_is_string` | 1L | "abc" | — | false |
| 6 | `should_accept_integer_target_id` | 1L | Integer(1) | blog.userId=1L | true |

---

## UserPermissionValidator

| # | 方法 | userId | targetId | 断言 |
|---|------|--------|---------|------|
| 1 | `should_return_true_when_self` | 1L | 1L | true |
| 2 | `should_return_false_when_other` | 1L | 2L | false |
| 3 | `should_return_false_when_target_null` | 1L | null | false |
