# Guard 守卫层测试（阶段二 · 按需）

> 仅当 `guard/` 包下代码变更时生成。

---

## ⛔ Guard 专属禁止

- **禁止在单元测试中连接真实 Redis**——`RateLimitPolicy` 必须 Mock `StringRedisTemplate`
- **禁止使用 `@SpringBootTest` 测试单独 Policy**——全部 `@ExtendWith(MockitoExtension.class)`
- **禁止 `GuardedToolCallback` 测试中真实调用 `delegate.call()`**——用 Mock 的 `ToolCallback`

---

## ToolGuardManager

### 注入方式

```java
@ExtendWith(MockitoExtension.class)
class ToolGuardManagerTest {
    @Mock ToolGuardPolicy policyA;
    @Mock ToolGuardPolicy policyB;
    private ToolGuardManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolGuardManager(List.of(policyA, policyB));
    }
}
```

### 必须生成的测试

| # | 方法 | Policy A | Policy B | 预期 |
|---|------|----------|----------|------|
| 1 | `should_allow_when_all_abstain` | ABSTAIN | ABSTAIN | ALLOW |
| 2 | `should_block_when_any_blocks` | ABSTAIN | BLOCK | BLOCK |
| 3 | `should_confirm_when_any_confirms` | ABSTAIN | CONFIRM | CONFIRM |
| 4 | `should_block_before_confirm` | CONFIRM | BLOCK | BLOCK（短路，B 不执行） |
| 5 | `should_skip_policy_on_exception` | 抛异常 | ALLOW | ALLOW |
| 6 | `should_allow_when_no_policies` | 空列表 | — | ALLOW |

---

## GuardedToolCallback

### 必须生成的测试

| # | 方法 | GuardResult | returnDirect | 断言 |
|---|------|-------------|--------------|------|
| 1 | `should_delegate_call_when_allowed` | ALLOW | false | `verify(delegate).call(payload)` |
| 2 | `should_return_error_json_when_blocked` | BLOCK | false | 返回 `{"error":"..."}` |
| 3 | `should_return_direct_text_when_blocked_and_return_direct` | BLOCK | true | 返回纯文本 |
| 4 | `should_return_confirm_json_when_confirm` | CONFIRM | false | 返回 `{"confirm":"..."}` |

### 测试代码模板

```java
@Test
void should_delegate_call_when_allowed() {
    ToolCallback delegate = mock(ToolCallback.class);
    ToolDefinition def = ToolDefinition.builder("testTool", "desc").build();
    when(delegate.getToolDefinition()).thenReturn(def);
    when(delegate.call(anyString())).thenReturn("result");

    GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv", 1L, false);
    String result = guarded.call("{}");

    assertThat(result).as("ALLOW 时应返回 delegate 结果").isEqualTo("result");
    verify(delegate).call("{}");
}
```

---

## Policy 测试

### HighRiskListPolicy

```java
class HighRiskListPolicyTest {
    @Mock PromptGuardProperties properties;
    HighRiskListPolicy policy;

    @BeforeEach
    void setUp() {
        when(properties.getBlockTools()).thenReturn(List.of("deleteBlog"));
        policy = new HighRiskListPolicy(properties);
    }

    @Test void should_block_when_tool_in_list() { /* ... */ }
    @Test void should_abstain_when_tool_not_in_list() { /* ... */ }
    @Test void should_abstain_when_list_empty() { when(properties.getBlockTools()).thenReturn(List.of()); ... }
}
```

### ConfirmToolPolicy（同上结构，换 `getConfirmTools()`）

### PatternMatchPolicy（需 Mock `getBlockPatterns` / `getConfirmPatterns`）

| # | 方法 | 配置 | 上下文 | 预期 |
|---|------|------|--------|------|
| 1 | `should_block_when_tool_matches_block_pattern` | blockPatterns=[{toolName:".*[Dd]elete.*"}] | toolName="deleteBlog" | BLOCK |
| 2 | `should_confirm_when_arg_matches_confirm_pattern` | confirmPatterns=[{arguments:".*confirm.*"}] | args='{"confirm":true}' | CONFIRM |
| 3 | `should_abstain_when_no_match` | 空规则 | — | ABSTAIN |

### RateLimitPolicy

| # | 方法 | Redis 行为 | 配置 | 预期 |
|---|------|-----------|------|------|
| 1 | `should_block_when_over_limit` | increment→31 | maxPerSession=30 | BLOCK |
| 2 | `should_abstain_when_under_limit` | increment→15 | maxPerSession=30 | ABSTAIN |
| 3 | `should_abstain_when_redis_throws` | 抛异常 | — | ABSTAIN（降级） |
| 4 | `should_abstain_when_conversation_id_null` | — | conversationId=null | ABSTAIN |

### RateLimitPolicy 测试代码

```java
@Test
void should_block_when_over_limit() {
    when(stringRedisTemplate.opsForValue().increment(any())).thenReturn(31L);
    when(config.getMaxPerSession()).thenReturn(30);

    ToolInvocationContext ctx = ToolInvocationContext.builder()
        .conversationId("conv-1").build();
    Vote vote = policy.vote(ctx);

    assertThat(vote).as("超限应拦截").isEqualTo(Vote.BLOCK);
    verify(stringRedisTemplate).expire(any(), any()); // 首次调用会设过期
}
```
