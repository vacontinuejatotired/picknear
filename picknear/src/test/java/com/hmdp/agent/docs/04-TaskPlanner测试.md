# TaskPlanner 测试（阶段二 · 按需）

> 仅当 `TaskPlanner.java` 变更时生成。

---

## ⛔ TaskPlanner 专属禁止

- **禁止 `decompose` 测试真实调 ChatClient**——必须 Mock `chatClient.prompt().user().call().content()` 返回固定 JSON
- **禁止单元测试中验证 `SseEmitter` 推送格式**——那是链路打通测的，这里只测 plannAndExecute 的逻辑分支
- **禁止在测 `validatePlan` 时带 `history`**——单独测时给空 history

---

## decompose 测试

### 注入方式

```java
@ExtendWith(MockitoExtension.class)
class TaskPlannerTest {
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec responseSpec;
    @Mock ToolBeanCollector toolBeanCollector;
    @Mock ToolCallback toolCallback;
    @Mock ToolDefinition toolDef;

    @InjectMocks TaskPlanner taskPlanner;

    // 反射调用 decompose（包私有方法）
    private List<SubTask> decompose(String input, String response,
                                     ToolCallback[] toolCallbacks, TaskReport history) {
        Method method = TaskPlanner.class.getDeclaredMethod("decompose",
            String.class, String.class, ToolCallback[].class, TaskReport.class);
        method.setAccessible(true);
        return (List<SubTask>) method.invoke(taskPlanner, input, response, toolCallbacks, history);
    }
}
```

### 必须生成的测试

| # | 方法 | LLM 返回 | 工具列表 | 预期 |
|---|------|---------|---------|------|
| 1 | `should_return_tasks_when_valid_plan` | `[{"tool":"queryWeather","params":{"city":"北京"}}]` | [queryWeather] | 1 个 SubTask，type=TOOL_CALL，params={city:北京} |
| 2 | `should_return_empty_when_llm_returns_empty_array` | `[]` | [queryWeather] | 空列表 |
| 3 | `should_return_empty_when_llm_returns_invalid_json` | `not json` | — | 空列表（异常捕获） |
| 4 | `should_skip_unknown_tool` | `[{"tool":"nonExistent"}]` | [queryWeather] | 跳过，空列表 |
| 5 | `should_skip_completed_tool` | `[{"tool":"queryWeather"}]` | [queryWeather], history已完成 | 跳过，空列表 |
| 6 | `should_skip_final_failed_tool` | `[{"tool":"queryWeather"}]` | [queryWeather], finalFailed | 跳过 |
| 7 | `should_not_include_llm_reason` | `[{"tool":"queryWeather"}]` | [queryWeather] | 只有 TOOL_CALL，无 LLM_REASON 追加 |

---

## planAndExecute 主循环

### 必须生成的测试

需 Mock 更多依赖：

```java
@Mock SubTaskAgent subTaskAgent;
@Mock FeatureProperties featureProperties;
@Mock SubTaskProperties subTaskProperties;
@Mock FeatureProperties.SubAgent subAgentFeature;
```

| # | 方法 | 轮次行为 | 断言 |
|---|------|---------|------|
| 1 | `should_return_response_when_no_tasks` | decompose 返回空 | 返回 currentResponse（不进入 execute） |
| 2 | `should_update_response_after_sub_agent_execution` | decompose→1工具→SubTaskAgent返回 summary | currentResponse 被更新 |
| 3 | `should_stop_after_max_rounds` | 每轮都有任务（Mock 控制） | 只执行 5 轮，第 6 轮不执行 |

```java
// 主循环测试模板
@Test
void should_update_response_after_sub_agent_execution() {
    // Feature 开关 = true
    when(featureProperties.getSubagent()).thenReturn(subAgentFeature);
    when(subAgentFeature.isEnabled()).thenReturn(true);

    // Mock decompose 返回 1 个工具
    // 注意：decompose 会调 askAiForPlan(chatClient)，需 Mock LLM 返回
    when(responseSpec.content()).thenReturn("[{\"tool\":\"queryWeather\",\"params\":{\"city\":\"北京\"}}]");

    // Mock SubTaskAgent 返回结果
    SubTaskResult result = SubTaskResult.builder()
        .summary("查询结果：北京晴天")
        .allSuccess(true)
        .build();
    when(subTaskAgent.execute(any())).thenReturn(result);

    // 执行
    String finalResponse = taskPlanner.planAndExecute("北京天气", "好的", ctx, emitter);

    // 断言
    assertThat(finalResponse).as("应返回子 Agent 摘要").isEqualTo("查询结果：北京晴天");
}
```

---

## validatePlan（三层校验）

| # | 方法 | JSON | 工具列表 | 预期 |
|---|------|------|---------|------|
| 1 | `should_parse_valid_json_array` | `[{"tool":"A"}]` | [A] | 返回 1 个 task |
| 2 | `should_reject_non_array` | `{"tool":"A"}` | — | 空列表 |
| 3 | `should_reject_missing_tool_field` | `[{"params":{}}]` | — | 跳过 |
| 4 | `should_handle_null_params` | `[{"tool":"A"}]` | [A] | params={} |
| 5 | `should_handle_params_as_non_map` | `[{"tool":"A","params":"string"}]` | [A] | params={}（类型不匹配降级） |

---

## TaskReport

| # | 方法 | 条件 | 断言 |
|---|------|------|------|
| 1 | `should_mark_completed` | record(COMPLETED) | `isCompleted("tool")` = true |
| 2 | `should_mark_final_failed_when_retry_ge_1` | record(FAILED, retryCount=1) | `isFinalFailed("tool")` = true |
| 3 | `should_not_mark_final_failed_when_retry_0` | record(FAILED, retryCount=0) | `isFinalFailed("tool")` = false |
| 4 | `should_filter_final_failed_from_retry_list` | A(finalFailed)+B(failed) | `getFailedToolNames()`=[B] |

---

## 规划工具路由（紧凑目录 + 保底）

> 对应 `ToolRouter` / `CompactCatalogBuilder`（`com.hmdp.agent.routing`），TaskPlanner 只做编排。

### 依赖注入（setUp 必需）

```java
@Mock ToolRouter toolRouter;
@Mock FeatureProperties.ToolRouting toolRoutingFeature;

lenient().when(featureProperties.getToolRouting()).thenReturn(toolRoutingFeature);
lenient().when(toolRouter.buildCatalog(anyBoolean(), any(ToolCallback[].class), any(TaskReport.class)))
        .thenReturn("渲染后工具目录");
```

> `toolRoutingFeature.isEnabled()` mock 默认 false → 未显式开启的测试自动走回退路径（全量目录），保持绿。

### 必须生成的测试

| # | 方法 | 场景 | 断言 |
|---|------|------|------|
| 1 | `should_retry_with_full_catalog_when_uncertain_marker` | enabled=true；LLM 首次返回 `__UNCERTAIN__`，二次返回 `[]` | `buildCatalog(true)` 与 `buildCatalog(false)` 各一次；最终空计划 |
| 2 | `CompactCatalogBuilderTest#should_build_compact_line_with_first_sentence_and_params` | queryWeather（含 city 参数） | 行格式 `- queryWeather: 首句（参数：city）` |
| 3 | `CompactCatalogBuilderTest#should_skip_completed_and_final_failed_tools` | history 已完成/终失败 | 目录中不出现该工具 |
| 4 | `CompactCatalogBuilderTest#should_apply_override_for_publish_test_blog` | publishTestBlog 命中 OVERRIDES | 标签用「发博客/写博客/发布/发一篇」版本 |
| 5 | `CompactCatalogBuilderTest#should_truncate_overlong_tag_with_ellipsis` | 超 maxTagLength | 截断加 … |
| 6 | `ToolRouterTest#is_uncertain_should_detect_marker` | 命中/未命中/null | `isUncertain` 判定正确 |
