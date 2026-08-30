package com.hmdp.agent.prompt.repo;

import com.hmdp.agent.prompt.config.PromptProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PromptRepositoryAssembler} 单元测试（评审 13.2.3 / 方案 §4.5）。
 * <p>
 * 守护：type=langfuse → LangfusePromptRepository；type=none → Noop；
 * type=未知/缺省 → Noop；base-url 空时 langfuse 仍装配（Fail-Open 由内部逻辑处理）。
 * </p>
 */
class PromptRepositoryAssemblerTest {

    private final LangfusePromptRepository langfuse = new LangfusePromptRepository(new PromptProperties(), null);

    private PromptRepositoryAssembler assembler(String type) {
        PromptProperties props = new PromptProperties();
        if (type != null) {
            PromptProperties.Repository repo = new PromptProperties.Repository();
            repo.setType(type);
            props.setRepository(repo);
        }
        return new PromptRepositoryAssembler(langfuse, props);
    }

    @Test
    void typeLangfuse_shouldReturnLangfuseInstance() {
        assertThat(assembler("langfuse").assemble()).isSameAs(langfuse);
    }

    @Test
    void typeNone_shouldReturnNoop() {
        assertThat(assembler("none").assemble()).isSameAs(NoopPromptRepository.INSTANCE);
    }

    @Test
    void blankType_shouldReturnLangfuse_defaultCompatibility() {
        // null/未设置 → 缺省 langfuse（兼容现状）
        assertThat(assembler(null).assemble()).isSameAs(langfuse);
    }

    @Test
    void emptyType_shouldReturnNoop_failOpen() {
        // 空串/空白 → trim 后 = ""，不匹配 "langfuse" → Fail-Open Noop（符合装配器语义）
        assertThat(assembler("  ").assemble()).isSameAs(NoopPromptRepository.INSTANCE);
    }

    @Test
    void unknownType_shouldFailOpenToNoop() {
        assertThat(assembler("redis").assemble()).isSameAs(NoopPromptRepository.INSTANCE);
    }

    @Test
    void noopFetch_shouldAlwaysReturnEmpty() {
        assertThat(NoopPromptRepository.INSTANCE.fetch("anything")).isEmpty();
    }

    @Test
    void noopSeed_shouldAlwaysReturnFalse() {
        assertThat(NoopPromptRepository.INSTANCE.seed("key", "content")).isFalse();
    }

    @Test
    void langfuseFetch_unconfigured_shouldReturnEmpty_failOpen() {
        // LangfusePromptRepository base-url 为空 → isConfigured=false → fetch 返回 empty
        assertThat(langfuse.fetch("any-key")).isEmpty();
    }
}