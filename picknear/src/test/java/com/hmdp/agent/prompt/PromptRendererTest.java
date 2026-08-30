package com.hmdp.agent.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptRenderer — {{var}} 占位符替换测试。
 * <p>
 * 覆盖：正常替换、缺失变量保留字面量、重复变量、单花括号 JSON 不受影响、$/\ 值安全、null 模板/变量。
 * </p>
 */
class PromptRendererTest {

    @Test
    void should_replace_variables() {
        String result = PromptRenderer.render("你好 {{name}}，今天{{city}}", Map.of("name", "小明", "city", "北京"));
        assertThat(result).isEqualTo("你好 小明，今天北京");
    }

    @Test
    void should_keep_literal_when_variable_missing() {
        String result = PromptRenderer.render("你好 {{missing}}", Map.of());
        assertThat(result).as("缺失变量应保留字面量").isEqualTo("你好 {{missing}}");
    }

    @Test
    void should_reuse_variable_for_multiple_occurrences() {
        String result = PromptRenderer.render("{{v}} 和 {{v}}", Map.of("v", "X"));
        assertThat(result).isEqualTo("X 和 X");
    }

    @Test
    void should_not_touch_single_braces_json() {
        String result = PromptRenderer.render("{\"status\":\"{{status}}\"}", Map.of("status", "ok"));
        assertThat(result).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void should_safely_insert_dollar_and_backslash() {
        String result = PromptRenderer.render("值：{{v}}", Map.of("v", "$1\\2"));
        assertThat(result).as("$ 和 \\ 不应被误替换").isEqualTo("值：$1\\2");
    }

    @Test
    void should_handle_null_template() {
        assertThat(PromptRenderer.render(null, Map.of("a", "b"))).as("null 模板应返回空串").isEmpty();
    }

    @Test
    void should_handle_null_vars() {
        assertThat(PromptRenderer.render("原样", null)).isEqualTo("原样");
    }
}
