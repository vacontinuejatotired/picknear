package com.hmdp.agent.prompt.repo;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Noop 提示词仓库（Fail-Open：未配置远程 / {@code type=none} 时注入）。
 * <p>
 * fetch 恒空、seed 恒 false、evictAll 无操作——等价现状"未配置时走内置模板"行为。
 * </p>
 */
@Slf4j
public class NoopPromptRepository implements RemotePromptRepository {

    public static final NoopPromptRepository INSTANCE = new NoopPromptRepository();

    private NoopPromptRepository() {
    }

    @Override
    public Optional<String> fetch(String key) {
        return Optional.empty();
    }

    @Override
    public boolean seed(String key, String content) {
        log.debug("[prompt] NoopPromptRepository: seed ignored key={}", key);
        return false;
    }

    @Override
    public void evictAll() {
        // no-op
    }
}