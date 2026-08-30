# Config 配置层测试

> 配置类多为 Data Class 和 Bean 定义，逻辑简单，**仅在配置解析逻辑变更时才需测试**。

---

## ⛔ Config 专属禁止

- **禁止对 `AgentConfig` 的 Bean 定义做集成测试**——`ChatClient`、`ChatMemory`、线程池的创建由 Spring 容器保证，单元测试不需要覆盖
- **禁止在 Properties 测试中启动 `@SpringBootTest`**——直接 `new` 配置对象调用 setter/getter

---

## Properties 值对象测试

```java
class SubTaskPropertiesTest {
    @Test void should_use_default_values() {
        SubTaskProperties props = new SubTaskProperties();
        assertThat(props.getMaxRetries()).as("默认重试 3 次").isEqualTo(3);
        assertThat(props.getTimeout()).as("默认超时 30s").isEqualTo(Duration.ofSeconds(30));
    }

    @Test void should_accept_custom_values() {
        SubTaskProperties props = new SubTaskProperties();
        props.setMaxRetries(5);
        props.setTotalTimeout(Duration.ofSeconds(120));
        assertThat(props.getMaxRetries()).isEqualTo(5);
        assertThat(props.getTotalTimeout()).isEqualTo(Duration.ofSeconds(120));
    }
}
```

| # | 类 | 测什么 |
|---|----|--------|
| 1 | SubTaskProperties | 默认值、setter/getter |
| 2 | FeatureProperties | subagent.enabled 默认 true |
| 3 | PromptGuardProperties | blockTools/confirmTools 默认空列表 |
| 4 | PromptGuardProperties.PatternRule | toolName/arguments setter/getter |
| 5 | PromptGuardProperties.RateLimit | maxPerSession=30, windowSeconds=60 |
