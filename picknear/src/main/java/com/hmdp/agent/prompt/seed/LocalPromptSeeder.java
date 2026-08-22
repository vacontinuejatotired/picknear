package com.hmdp.agent.prompt.seed;

import com.hmdp.agent.prompt.config.PromptProperties;
import com.hmdp.agent.prompt.repo.BuiltinPromptRepository;
import com.hmdp.agent.prompt.repo.LocalPromptRepository;
import com.hmdp.agent.prompt.repo.RemotePromptRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 本地提示词种子：启动时从 Langfuse 拉取所有提示词并备份到本地数据库。
 * <p>
 * 作用：Langfuse 不可用时，本地数据库作为兜底，确保系统正常运行。
 * </p>
 */
@Slf4j
@Component
public class LocalPromptSeeder {

    private final PromptSeeder promptSeeder;
    private final RemotePromptRepository remote;
    private final LocalPromptRepository local;
    private final BuiltinPromptRepository builtin;
    private final PromptProperties props;

    public LocalPromptSeeder(PromptSeeder promptSeeder, RemotePromptRepository remote,
                             LocalPromptRepository local, BuiltinPromptRepository builtin,
                             PromptProperties props) {
        this.promptSeeder = promptSeeder;
        this.remote = remote;
        this.local = local;
        this.builtin = builtin;
        this.props = props;
    }

    /**
     * 启动时同步提示词到本地数据库（Langfuse 可用时从 Langfuse 拉取，否则从内置模板加载）
     */
    @PostConstruct
    void syncToLocal() {
        if (!props.isEnabled()) {
            log.info("[prompt-seeder] 提示词功能已禁用，跳过本地同步");
            return;
        }

        List<String> keys = promptSeeder.listAllKeys();
        int synced = 0;

        for (String key : keys) {
            try {
                Optional<String> content;

                if (props.isConfigured()) {
                    // 先尝试从 Langfuse 拉取
                    content = remote.fetch(key);
                } else {
                    content = Optional.empty();
                }

                if (content.isEmpty()) {
                    // Langfuse 没有或不可用，从内置模板加载
                    content = builtin.load(key);
                }

                if (content.isPresent()) {
                    local.save(key, content.get(), props.getDefaultLabel(),
                            props.isConfigured() ? "langfuse" : "builtin");
                    synced++;
                } else {
                    log.warn("[prompt-seeder] 提示词缺失 key={}", key);
                }
            } catch (Exception e) {
                log.warn("[prompt-seeder] 同步失败 key={}, err={}", key, e.getMessage());
            }
        }

        log.info("[prompt-seeder] 本地同步完成 {}/{} keys", synced, keys.size());
    }
}
