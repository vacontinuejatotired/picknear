# SseUtils 工具类测试

> 纯函数，无外部依赖，**最不容易翻车**。仅当 `SseUtils` 或 `SseEventConstants` 变更时生成。

---

## ⛔ 专属禁止

- **禁止 Mock 任何东西**——`SseUtils` 全是静态方法，无依赖
- **禁止使用 `@ExtendWith`**——不需要 Spring 也不需要 Mockito

---

## 必须生成的测试

```java
class SseUtilsTest {

    // ========== errorEvent ==========
    @Test void should_build_error_event() {
        String json = SseUtils.errorEvent("出错了");
        assertThat(json).as("应包含错误消息和 code")
            .contains("\"error\":\"出错了\"")
            .contains("\"code\":5001");
    }

    @Test void should_handle_null_error_message() {
        String json = SseUtils.errorEvent(null);
        assertThat(json).contains("\"error\":\"\"");
    }

    // ========== escapeJson ==========
    @Test void should_escape_special_characters() {
        assertThat(SseUtils.escapeJson("a\"b\\c\nd")).isEqualTo("a\\\"b\\\\c\\nd");
    }

    @Test void should_return_empty_string_for_null() {
        assertThat(SseUtils.escapeJson(null)).isEqualTo("");
    }

    // ========== progressEvent ==========
    @Test void should_build_progress_event() {
        String json = SseUtils.progressEvent("planning", "规划完成");
        assertThat(json).contains("\"stage\":\"planning\"");
        assertThat(json).contains("\"text\":\"规划完成\"");
    }

    // ========== stepEvent ==========
    @Test void should_build_step_event() {
        String json = SseUtils.stepEvent("queryWeather", "RUNNING");
        assertThat(json).contains("\"toolName\":\"queryWeather\"");
        assertThat(json).contains("\"status\":\"RUNNING\"");
    }

    // ========== toJson（正常序列化）==========
    @Test void should_serialize_map_to_json() {
        String json = SseUtils.toJson(Map.of("key", "value", "num", 123));
        assertThat(json).contains("\"key\":\"value\"");
        assertThat(json).contains("\"num\":123");
    }

    // ========== safeSend（SseEmitter 异常）==========
    @Test void should_not_throw_when_send_fails() {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("closed"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        // 不应抛异常
        SseUtils.safeSend(emitter, "data");
    }
}
```

---

## SseEventConstants 校验

| # | 常量 | 值 | 前端依赖 |
|---|------|-----|---------|
| 1 | STAGE_MERGING | `"merging"` | AiChat.vue 依赖此 stage 响应 |
| 2 | STAGE_CONFIRM | `"confirm"` | AiChat.vue 依赖 |
| 3 | TEXT_CONFIRM_WAIT | `"需要确认，暂停规划"` | 用户可见文案 |

```java
class SseEventConstantsTest {
    @Test void should_have_correct_stage_values() {
        assertThat(SseEventConstants.STAGE_MERGING).isEqualTo("merging");
        assertThat(SseEventConstants.STAGE_CONFIRM).isEqualTo("confirm");
    }
}
```
