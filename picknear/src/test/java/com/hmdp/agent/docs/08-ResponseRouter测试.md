# AiResponseRouter 测试（阶段二 · 按需）

> **注意**：AiResponseRouter 的路由行为已在 `01-端到端链路打通.md` 链路C 中覆盖。此处仅做冗余记录，生成时直接参考链路C。

---

## ⛔ AiResponseRouter 专属禁止

- **禁止用 `argThat(d -> d.toString().contains(...))` 匹配 SseEventBuilder 内容**——`SseEventBuilderImpl.toString()` 不暴露数据。只 verify `send()` 被调用即可，数据格式在 `SseUtilsTest` 中覆盖
- **禁止在测试中 `new SseEmitter()`**——必须 `mock(SseEmitter.class)`，否则 send() 真实写入会抛异常
- **禁止测试 TaskPlanner 内部逻辑**——那是 `04-TaskPlanner测试.md` 的职责

---

## 注入

```java
@ExtendWith(MockitoExtension.class)
class AiResponseRouterRouteTest {
    @Mock TaskPlanner taskPlanner;
    @Mock SseEmitter emitter;
    @InjectMocks AiResponseRouter router;
}
```

---

## 必须生成的测试

| # | 方法 | HookResult | 断言 |
|---|------|-----------|------|
| 1 | `should_enter_task_planner_when_planning` | PLANNING | `verify(taskPlanner).planAndExecuteAsync(...)` |
| 2 | `should_push_error_when_block` | BLOCK | `verify(emitter).send(any(SseEventBuilder.class))` + `.complete()` |
| 3 | `should_push_reply_when_pass` | PASS | `verify(emitter).send(any(SseEventBuilder.class))` + `.complete()` |
| 4 | `should_push_replaced_text_when_replace` | REPLACE | `verify(emitter).send(any(SseEventBuilder.class))` + `.complete()` |
| 5 | `should_send_error_when_emitter_throws` | PASS + send 抛 IOException | 不抛异常（catch 后 `completeWithError`） |

> 内容验证：`SseEmitter.SseEventBuilder` 没有暴露数据的 getter，所以 verify 内容分两层：
> 1. **这里只验证 send() 被调用**（路由正确）
> 2. **格式验证在 SseUtilsTest**（文档 13）
