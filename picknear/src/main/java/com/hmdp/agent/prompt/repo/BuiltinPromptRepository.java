package com.hmdp.agent.prompt.repo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置模板仓库：读取 {@code classpath:prompts/{key}.txt} 作为 Langfuse 不可用时的兜底。
 * <p>
 * 静态文件首读后缓存在内存（只读，无需过期）。文件缺失/为空 → WARN + empty（调用方回退更上层）。
 * </p>
 */
@Slf4j
@Component
public class BuiltinPromptRepository {

    private static final String PROMPT_DIR = "prompts/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public Optional<String> load(String key) {
        String cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        String path = PROMPT_DIR + key + ".txt";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("[prompt] 内置模板不存在: {}", path);
                return Optional.empty();
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                log.warn("[prompt] 内置模板为空: {}", path);
                return Optional.empty();
            }
            cache.put(key, content);
            return Optional.of(content);
        } catch (IOException e) {
            log.warn("[prompt] 读取内置模板失败 key={}", key, e);
            return Optional.empty();
        }
    }
}
