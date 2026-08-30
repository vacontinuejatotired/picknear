package com.hmdp.agent.observability.core;

import com.hmdp.agent.observability.support.AttributeSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SpanNameEncoder} 纯单元测试（评审 13.3.2：从 ToolGuardGate 下沉的载荷加工逻辑回归防线）。
 * <p>
 * 守护点：guard 语义拼接格式、紧凑参数化（去引号/空白 → 脱敏 → 限长）、
 * 空/{} 参数省略段、整体清洗限长。
 * </p>
 */
class SpanNameEncoderTest {

    private final SpanNameEncoder encoder = new SpanNameEncoder(new AttributeSanitizer());

    @Test
    void encodeGuardSemantic_shouldJoinDecisionToolModelArgs() {
        assertThat(encoder.encodeGuardSemantic("ALLOW", "query-weather", "qwen-plus-2025-07-28",
                "{\"city\": \"北京\"}"))
                .isEqualTo("ALLOW.query-weather.qwen-plus-2025-07-28.{city:北京}");
    }

    @Test
    void encodeGuardSemantic_shouldOmitBlankSegments() {
        // 无模型名、无参数：只剩 decision.toolName
        assertThat(encoder.encodeGuardSemantic("BLOCK", "deleteBlog", "", "{}")).isEqualTo("BLOCK.deleteBlog");
        // 无参数段
        assertThat(encoder.encodeGuardSemantic("ALLOW", "queryShop", "model-x", null))
                .isEqualTo("ALLOW.queryShop.model-x");
    }

    @Test
    void encodeGuardSemantic_shouldReturnNull_whenDecisionOrToolBlank() {
        assertThat(encoder.encodeGuardSemantic("", "tool", null, null)).isNull();
        assertThat(encoder.encodeGuardSemantic("ALLOW", "  ", null, null)).isNull();
    }

    @Test
    void compactArgs_shouldStripJsonQuotesWhitespace_andTruncate() {
        assertThat(encoder.compactArgs("{\"city\": \"北京\", \"limit\": 3}"))
                .isEqualTo("{city:北京,limit:3}");
        // 超长限 40
        String longArgs = "{\"k\": \"" + "a".repeat(100) + "\"}";
        assertThat(encoder.compactArgs(longArgs)).hasSizeLessThanOrEqualTo(40 + 3); // truncate 带省略号
    }

    @Test
    void compactArgs_shouldReturnEmpty_forNullOrEmptyOrEmptyJson() {
        assertThat(encoder.compactArgs(null)).isEmpty();
        assertThat(encoder.compactArgs("   ")).isEmpty();
        assertThat(encoder.compactArgs("{}")).isEmpty();
    }

    @Test
    void compactArgs_shouldMaskPii_throughSanitizer() {
        // 手机号在入 span 名前脱敏（统一脱敏出口）
        assertThat(encoder.compactArgs("{\"phone\": \"13812348000\"}"))
                .contains("****").doesNotContain("1381234");
    }

    @Test
    void sanitizeName_shouldStripControlWhitespace_andCapLength() {
        assertThat(encoder.sanitizeName("agent.tool_call.query Shop")).isEqualTo("agent.tool_call.queryShop");
        String longName = "x".repeat(200);
        assertThat(encoder.sanitizeName(longName)).hasSize(SpanNameEncoder.NAME_MAX_CHARS);
        assertThat(encoder.sanitizeName(null)).isNull();
    }
}