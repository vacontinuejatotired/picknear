package com.hmdp.agent.observability;

import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.observability.support.SanitizeLevel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字段注册表 {@link AgentField} 纯单元测试（无 Spring 上下文）。
 * <p>
 * 守护点：key 唯一/合法、byKey 往返、specs 非空、参数化 key 填充与段数校验、
 * SanitizeLevel → AttributeSanitizer 脱敏映射（注册表即真相源，测试防漂移）。
 * </p>
 */
class AgentFieldTest {

    private static final Pattern FIXED_KEY_PATTERN = Pattern.compile("[a-z0-9_.]+");

    @Test
    void fixedFieldKeys_shouldBeUniqueNonBlank_andMatchKeyPattern() {
        assertThat(AgentField.values()).as("字段注册表不应为空").isNotEmpty();

        long distinctKeys = Arrays.stream(AgentField.values()).map(AgentField::key).distinct().count();
        assertThat(distinctKeys).as("固定 key 不应重复").isEqualTo(AgentField.values().length);

        for (AgentField field : AgentField.values()) {
            assertThat(field.key()).as(field.name() + " key 非空").isNotBlank();
            if (!field.key().contains("{")) {
                assertThat(FIXED_KEY_PATTERN.matcher(field.key()).matches())
                        .as(field.name() + " key=%s 应匹配 %s", field.key(), FIXED_KEY_PATTERN.pattern())
                        .isTrue();
            }
        }
    }

    @Test
    void byKey_shouldRoundTripFixedFields() {
        for (AgentField field : AgentField.values()) {
            if (field.key().contains("{")) {
                continue; // 参数化字段模板不可反查（运行时 key 无法枚举）
            }
            assertThat(AgentField.byKey(field.key())).as(field.name() + " byKey 往返").contains(field);
        }
        assertThat(AgentField.byKey("agent.不存在的key")).isEmpty();
    }

    @Test
    void everyField_shouldDeclareAtLeastOneSpanType() {
        for (AgentField field : AgentField.values()) {
            assertThat(field.specs()).as(field.name() + " 应声明所属 span 类型").isNotEmpty();
        }
    }

    @Test
    void parameterizedKey_shouldFillSegmentsInOrder() {
        assertThat(AgentField.TOOL_ENTRY_NAME.key("0")).isEqualTo("tool.0.name");
        assertThat(AgentField.TOOL_ENTRY_STATUS.key("1")).isEqualTo("tool.1.status");
        assertThat(AgentField.GUARD_POLICY_ENTRY.key("RateLimit")).isEqualTo("guard.policy.RateLimit");
    }

    @Test
    void parameterizedKey_shouldRejectSegmentCountMismatch() {
        assertThatThrownBy(() -> AgentField.TOOL_ENTRY_NAME.key("0", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        // 零段：视为固定 key，返回模板原文（不抛）
        assertThat(AgentField.TOOL_ENTRY_NAME.key()).isEqualTo("tool.{i}.name");
    }

    @Test
    void sanitizeLevel_shouldTruncateByLimit() {
        AttributeSanitizer sanitizer = new AttributeSanitizer();

        String summary = "1".repeat(300);
        assertThat(SanitizeLevel.SUMMARY.sanitize(sanitizer, summary)).hasSize(200 + 1);

        String diagnostic = "x".repeat(5000);
        assertThat(SanitizeLevel.DIAGNOSTIC.sanitize(sanitizer, diagnostic)).hasSize(4096 + 1);
    }

    @Test
    void sanitizeLevel_shouldMaskPii() {
        AttributeSanitizer sanitizer = new AttributeSanitizer();
        // 不锁死具体脱敏形状（既有 AttributeSanitizer 行为），只验证中间段被掩码
        String masked = SanitizeLevel.SUMMARY.sanitize(sanitizer, "13812348000");
        assertThat(masked).contains("****").doesNotContain("1234");
    }
}
