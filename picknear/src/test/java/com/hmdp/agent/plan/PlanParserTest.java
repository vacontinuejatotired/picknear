package com.hmdp.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.plan.model.ParsedPlan;
import com.hmdp.agent.plan.support.PlanParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanParser — wire format 解析测试。
 */
class PlanParserTest {

    private final PlanParser parser = new PlanParser(new ObjectMapper());

    @Test
    void should_parse_object_format_with_markers() {
        String raw = "===PLAN_START===\n"
                + "{\"intents\":[\"查询→博客\",\"查询→天气\"],\"plan\":[{\"tool\":\"queryWeather\",\"params\":{\"city\":\"长沙\"}}]}"
                + "\n===PLAN_END===";

        ParsedPlan parsed = parser.parse(raw);

        assertThat(parsed.declaredIntents()).as("第一段意图").containsExactly("查询→博客", "查询→天气");
        assertThat(parsed.entries()).hasSize(1);
        assertThat(parsed.entries().get(0).get("tool")).isEqualTo("queryWeather");
    }

    @Test
    void should_parse_legacy_array_format() {
        ParsedPlan parsed = parser.parse("[{\"tool\":\"queryWeather\",\"params\":{\"city\":\"北京\"}}]");

        assertThat(parsed.declaredIntents()).as("旧数组格式无意图声明").isEmpty();
        assertThat(parsed.entries()).hasSize(1);
        assertThat(parsed.entries().get(0).get("tool")).isEqualTo("queryWeather");
    }

    @Test
    void should_extract_nested_object_without_markers() {
        // 无标记兜底：首个 { 起按深度计数提取完整嵌套对象（修复原 findMatchingClose 只找第一个 } 的截断问题）
        String raw = "好的，我来处理。{\"intents\":[\"查询→博客\"],\"plan\":[{\"tool\":\"queryBlogById\",\"params\":{\"blogId\":\"1\"}}]}完毕";

        ParsedPlan parsed = parser.parse(raw);

        assertThat(parsed.declaredIntents()).containsExactly("查询→博客");
        assertThat(parsed.entries()).hasSize(1);
        assertThat(parsed.entries().get(0).get("tool")).isEqualTo("queryBlogById");
    }

    @Test
    void should_return_empty_for_bad_json() {
        assertThat(parser.parse("不是 JSON").entries()).isEmpty();
        assertThat(parser.parse(null).entries()).isEmpty();
        assertThat(parser.parse("").entries()).isEmpty();
    }

    @Test
    void should_return_empty_for_scalar_json() {
        assertThat(parser.parse("\"字符串\"").entries()).isEmpty();
        assertThat(parser.parse("42").entries()).isEmpty();
    }

    @Test
    void should_handle_missing_plan_field() {
        ParsedPlan parsed = parser.parse("{\"intents\":[\"查询→博客\"]}");
        assertThat(parsed.entries()).as("缺 plan 字段按空处理").isEmpty();
    }
}
