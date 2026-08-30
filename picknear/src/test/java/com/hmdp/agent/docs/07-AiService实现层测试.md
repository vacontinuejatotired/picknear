# AiService 实现层测试

> **注意**：链路 A/B 的端到端测试已在 `01-端到端链路打通.md` 覆盖。
> 本文档仅补充 **单元级** 的 `processHookResult` 私有方法测试（反射调用）。

---

## ⛔ AiService 层专属禁止

- **禁止重复测链路 A/B 端到端场景**——那是 `01-端到端链路打通.md` 的职责
- **禁止在 processHookResult 测试中调用 ChatClient**——processHookResult 只处理 HookResult，不涉及 LLM
- **禁止反射调用私有方法时使用 PowerMock**——用标准 `Class.getDeclaredMethod()` + `setAccessible(true)`

---

## processHookResult（反射测试）

该方法为 `private`，需通过反射调用：

```java
private String invokeProcessHookResult(HookResult hookResult, String content, String convId) throws Exception {
    Method method = AiServiceImpl.class.getDeclaredMethod("processHookResult",
        HookResult.class, String.class, String.class);
    method.setAccessible(true);
    return (String) method.invoke(aiService, hookResult, content, convId);
}
```

| # | 方法 | HookResult | content | 断言 |
|---|------|-----------|---------|------|
| 1 | `should_return_null_when_blocked` | BLOCK | "原始" | 返回 null |
| 2 | `should_return_replaced_text_when_replaced` | REPLACE("替代文本") | "原始" | 返回 "替代文本" (不清洗 history) |
| 3 | `should_clean_history_when_replaced_with_history` | replaceWithHistory("替代", List.of(...)) | "原始" | verify chatMemory.clear + chatMemory.add |
| 4 | `should_return_original_when_passed` | PASS | "原始" | 返回 "原始" |
| 5 | `should_return_original_when_unknown_decision` | null (兜底) | "原始" | 返回 "原始" |
