# SubTaskAgent 测试（阶段二 · 按需）

> 仅当 `SubTaskAgent.java` 或 `SubAgentPromptBuilder.java` 变更时生成。

---

## ⛔ SubTaskAgent 专属禁止

- **禁止在 `executeWithRetry` 测试中用 `Thread.sleep` 等真实退避**——用 `@InjectMocks` + 快速 retryBackoff=1ms 或直接测 `execute` 方法让 AI 对退避时间的验证用 verify 间接确认
- **禁止在 `parseResult` 测试中调真实 ObjectMapper 的 readValue 时依赖真实响应格式**——自行构造含 `===DATA_SNAPSHOT===` 的字符串
- **禁止对 `executeWithRetry` 中的 `Thread.sleep` 做精确的计时验证**——只需验证重试次数和最终返回值

---

## 注入与 setUp

```java
@ExtendWith(MockitoExtension.class)
class SubTaskAgentTest {
    @Mock @Qualifier("subAgentChatClient") ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec responseSpec;
    @Mock ToolBeanCollector toolBeanCollector;
    @Mock ToolCallback weatherCallback;
    @Mock ToolDefinition weatherDef;

    @InjectMocks SubTaskAgent subTaskAgent;

    @BeforeEach
    void setUp() {
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.tools(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);

        lenient().when(weatherDef.name()).thenReturn("queryWeather");
        lenient().when(weatherCallback.getToolDefinition()).thenReturn(weatherDef);
    }
}
```

---

## execute（主入口）

### 必须生成的测试

| # | 方法 | 关键 Mock | 预期 |
|---|------|----------|------|
| 1 | `should_return_summary_when_single_tool_succeeds` | responseSpec.content() 返回含 SNAPSHOT 的完整回复 | summary 非空，allSuccess=true |
| 2 | `should_report_partial_failure` | SNAPSHOT 中一个 status=ok 一个 status=error | allSuccess=false，errors 非空 |
| 3 | `should_return_friendly_message_when_all_retries_fail` | responseSpec.content() 连续抛异常 | summary="⚠️ 服务暂时不可用" |
| 4 | `should_return_original_response_when_no_tools_available` | filterCallbacks 返回空 | summary=plan.getCurrentResponse() |
| 5 | `should_invoke_callback_on_start_and_merge` | callback 非 null | verify(callback).onExecuteStart() + onMergeStart() |
| 6 | `should_not_throw_when_callback_is_null` | callback=null | 正常返回，不抛 NPE |

### 测试数据构造

```java
// 含 SNAPSHOT 的 LLM 回复
String SNAPSHOT_CONTENT = """
    北京今天晴天。
    ===DATA_SNAPSHOT===
    {"queryWeather":{"status":"ok","data":"北京晴天 25°C"}}
    ===DATA_SNAPSHOT_END===
    """;

String SNAPSHOT_PARTIAL_FAIL = """
    部分查询失败。
    ===DATA_SNAPSHOT===
    {"queryWeather":{"status":"ok","data":"晴天"},"queryBlogs":{"status":"error","message":"无权限"}}
    ===DATA_SNAPSHOT_END===
    """;

String CONTENT_WITHOUT_SNAPSHOT = "纯文本回复，无快照标记。";

String CONTENT_LONG_DATA = "摘要。\n===DATA_SNAPSHOT===\n"
    + "{\"queryWeather\":{\"status\":\"ok\",\"data\":\"" + "A".repeat(600) + "\"}}\n"
    + "===DATA_SNAPSHOT_END===";
```

---

## filterCallbacks

| # | 方法 | 条件 | 预期 |
|---|------|------|------|
| 1 | `should_filter_only_allowed_tools` | toolNames=["queryWeather"] | 只返回 weatherCallback |
| 2 | `should_return_empty_when_no_match` | toolNames=["nonExistent"] | 空数组 |
| 3 | `should_deduplicate_tool_names` | toolNames=["A","A"] | 只包含 1 个 A |

---

## executeWithRetry

| # | 方法 | 条件 | 预期 |
|---|------|------|------|
| 1 | `should_return_on_first_success` | 第 1 次成功 | 返回 content |
| 2 | `should_retry_on_failure_and_succeed` | 第 1 次失败，第 2 次成功 | 调用 responseSpec.content() 2 次 |
| 3 | `should_return_null_when_all_retries_fail` | maxRetries=3，全部失败 | 返回 null |
| 4 | `should_inject_error_into_prompt_on_retry` | 第 1 次失败 | 第 2 次 prompt 含 "[系统提示] 上一次调用失败" |

---

## parseResult + extractSnapshot

| # | 方法 | content | 预期 |
|---|------|---------|------|
| 1 | `should_extract_snapshot_correctly` | 含标准 SNAPSHOT 标记 | -- |
| 2 | `should_return_summary_without_snapshot` | 无 SNAPSHOT 标记 | summary=全文 |
| 3 | `should_truncate_long_data` | data 600 字 | data 截断到 500 + "...(截断)" |
| 4 | `should_handle_invalid_snapshot_json` | SNAPSHOT 内非 JSON | 日志警告，rawResults={} |
| 5 | `should_return_null_when_content_null` | null | extractSnapshot → null |

---

## SubAgentPromptBuilder

| # | 方法 | plan 条件 | 断言 |
|---|------|----------|------|
| 1 | `should_include_param_constraints` | tasks[0] 有 params={city:"北京"} | prompt 含 `city 的值已由系统设定为 "北京"` |
| 2 | `should_skip_param_constraints_when_empty` | tasks[0].params={} | 无参数约束段 |
| 3 | `should_display_default_when_no_history` | historySummary=空 | prompt 含"（无）" |
| 4 | `should_number_tasks_correctly` | 2 个 task | 含"任务 1"和"任务 2" |

```java
// 构建器测试模板
@Test
void should_include_param_constraints() {
    SubTask task = SubTask.builder()
        .toolName("queryWeather")
        .params(Map.of("city", "北京"))
        .build();
    SubTaskPlan plan = SubTaskPlan.builder()
        .tasks(List.of(task))
        .build();

    String prompt = SubAgentPromptBuilder.build(plan);
    assertThat(prompt).as("参数约束应包含 city")
        .contains("city")
        .contains("北京");
}
```
