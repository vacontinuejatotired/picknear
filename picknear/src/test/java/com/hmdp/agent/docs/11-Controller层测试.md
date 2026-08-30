# Controller 层测试

> **链路 A/B 已在端到端文档覆盖**。此处仅补充 Controller 层的 conversationId 生成逻辑和模式切换。

---

## ⛔ Controller 专属禁止

- **禁止在 Controller 测试中启动完整 Web 容器**——用 `@WebMvcTest(ChatController.class)` 切片或纯 Mock 调用方法
- **禁止测试中依赖 AiService 真实实现**——用 `@MockBean AiService`

---

## @WebMvcTest 方式（推荐）

```java
@WebMvcTest(ChatController.class)
class ChatControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean AiService aiService;

    @Test void should_return_json_when_no_accept_header() throws Exception {
        mockMvc.perform(post("/agent/string/send")
                .param("content", "你好"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").value("你好"));
    }
}
```

### 必须生成的测试

| # | 方法 | Accept 头 | conversationId | 断言 |
|---|------|-----------|----------------|------|
| 1 | `should_return_json_when_no_accept_header` | 不传 | null | `status().isOk()` + `jsonPath("$.data.content")` |
| 2 | `should_return_sse_when_accept_is_event_stream` | `text/event-stream` | null | `content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)` |
| 3 | `should_generate_new_conversation_id` | JSON | null | `jsonPath("$.data.conversationId")` 非空 |
| 4 | `should_reuse_conversation_id` | JSON | "existing-id" | `jsonPath("$.data.conversationId")` = "existing-id" |
| 5 | `should_use_sse_timeout` | SSE | null | SseEmitter 超时参数 30min |

---

## 纯 Mock 方式（不启动 Web）

```java
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {
    @Mock AiService aiService;
    @InjectMocks ChatController controller;

    @Test void should_return_result_envelope() {
        when(aiService.chatReturnStringResult("你好", "default"))
            .thenReturn("你好！");

        Object result = controller.chat("你好", "", "default");

        assertThat(result).isInstanceOf(Result.class);
        Result<?> r = (Result<?>) result;
        assertThat(r.getData()).as("应包含 content 和 conversationId")
            .isInstanceOf(Map.class);
    }
}
```
