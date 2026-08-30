# Tool 工具层测试（阶段二 · 按需）

> 仅当 `tool/impl/` 下工具变更时生成。工具类逻辑简单，每个方法 1 条正常 + 1 条边界即可。

---

## ⛔ Tool 层专属禁止

- **禁止在单元测试中连接真实 DB**——`BlogTool` 的 `IBlogService`、`StatsQueryTool` 的三个 Service 必须 Mock
- **禁止在 `ToolBeanCollector` 测试中启动 Spring 容器构造 ApplicationContext**——直接通过 `setApplicationContext` 注入 `mock(ApplicationContext.class)`
- **`ToolContext` 参数必须 Mock**——`mock(ToolContext.class)` + `when(tc.getContext()).thenReturn(Map.of("userId", 1L))`

---

## ToolBeanCollector

| # | 方法 | 条件 | 断言 |
|---|------|------|------|
| 1 | `should_collect_active_tools` | 1 个 Bean 带 @TargetTool(active=true) | getToolCallbacks() 长度=该 Bean 的 @Tool 方法数 |
| 2 | `should_skip_inactive_tools` | 1 个 Bean active=false | 不包含其回调 |
| 3 | `should_wrap_with_guarded_tool_callback` | 1 个激活工具 | 回调类型为 GuardedToolCallback |
| 4 | `should_return_empty_when_no_tools` | 无 @TargetTool Bean | 空数组 |
| 5 | `should_update_conversation_id` | setConversationId("x") | getConversationId()="x" |

```java
// 构造测试
@Mock ApplicationContext appCtx;
@Mock ToolGuardManager guardManager;

@Test void should_collect_active_tools() {
    when(appCtx.getBeansWithAnnotation(TargetTool.class))
        .thenReturn(Map.of("weatherTool", new WeatherQueryTool()));

    ToolBeanCollector collector = new ToolBeanCollector(guardManager);
    collector.setApplicationContext(appCtx);

    assertThat(collector.getToolCallbacks())
        .as("WeatherQueryTool 有 1 个 @Tool 方法")
        .hasSize(1);
}
```

---

## BlogTool（3 个 @Tool 方法）

| # | 方法 | Mock | 断言 |
|---|------|------|------|
| 1 | `queryPublishedBlogs_should_return_user_blogs` | blogService.query().eq().orderByDesc().page() → Page | 返回 List<Blog> |
| 2 | `queryPublishedBlogs_should_limit_to_10` | Mock 15 条数据 | 返回最多 10 条 |
| 3 | `publishTestBlog_should_save_and_return` | blogService.save() → true | 返回 Blog, title="测试博客" |
| 4 | `publishTestBlog_should_return_null_when_save_fails` | save() → false | 返回 null |
| 5 | `queryBlogsByTitle_should_return_matches` | blogService.query().like().list() → List.of(blog) | 正常返回 |

---

## WeatherQueryTool

| # | 方法 | 输入 | 断言 |
|---|------|------|------|
| 1 | `should_return_weather_string` | "北京" | 包含 "北京" 和 "sunny" |
| 2 | `should_handle_null_city` | null | 不抛 NPE |

---

## StatsQueryTool（3 个统计方法）

| # | 方法 | Mock count | 断言 |
|---|------|-----------|------|
| 1 | `queryTotalBlogs_should_return_count` | blogService.count()=100 | 含 "100" |
| 2 | `queryTotalUsers_should_return_count` | userService.count()=50 | 含 "50" |
| 3 | `queryTotalShops_should_return_count` | shopService.count()=30 | 含 "30" |
| 4 | `should_handle_zero_counts` | count()=0 | 含 "0" |
