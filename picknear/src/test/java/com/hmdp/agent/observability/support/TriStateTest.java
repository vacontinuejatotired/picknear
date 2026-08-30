package com.hmdp.agent.observability.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TriState} 纯单元测试（评审 13.2.2 能力覆盖开关三态的解析与推导）。
 */
class TriStateTest {

    @Test
    void configString_shouldParseAuto_True_False() {
        assertThat(TriState.fromString("auto")).isEqualTo(TriState.AUTO);
        assertThat(TriState.fromString("true")).isEqualTo(TriState.ENABLED);
        assertThat(TriState.fromString("false")).isEqualTo(TriState.DISABLED);
        // 大小写不敏感 + 别名
        assertThat(TriState.fromString("TRUE")).isEqualTo(TriState.ENABLED);
        assertThat(TriState.fromString("enabled")).isEqualTo(TriState.ENABLED);
        assertThat(TriState.fromString("OFF")).isEqualTo(TriState.DISABLED);
    }

    @Test
    void unknownOrNull_shouldFallbackToAuto() {
        assertThat(TriState.fromString(null)).isEqualTo(TriState.AUTO);
        assertThat(TriState.fromString("garbage")).isEqualTo(TriState.AUTO);
        assertThat(TriState.fromString("  ")).isEqualTo(TriState.AUTO);
    }

    @Test
    void resolve_shouldFollowAutoValue_orForceOverride() {
        assertThat(TriState.AUTO.resolve(true)).isTrue();
        assertThat(TriState.AUTO.resolve(false)).isFalse();
        assertThat(TriState.ENABLED.resolve(false)).isTrue();  // 强制开，无视能力
        assertThat(TriState.DISABLED.resolve(true)).isFalse(); // 强制关，无视能力
    }
}