package com.hmdp.agent.prompt;

import com.hmdp.agent.prompt.config.PromptProperties;
import com.hmdp.agent.prompt.repo.RemotePromptRepository;
import com.hmdp.agent.prompt.seed.PromptSeeder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 启动预热：把全部提示词 key 先拉一次进 {@link LangfusePromptRepository} 的 contentCache，
 * 请求期全部命中，消除首读/TTL 过期后的同步远程延迟（每 key ~0.12s 串行累加）。
 * <p>
 * 未配置 Langfuse（base-url/basic-auth 空）或总开关关闭时跳过（Fail-Open）；
 * 预热失败由 {@code LangfusePromptRepository} 内部熔断兜底，不阻塞启动。
 * </p>
 */
@Slf4j
@Component
public class PromptCacheWarmer {

    private final PromptSeeder promptSeeder;
    private final RemotePromptRepository remote;
    private final PromptProperties props;

    public PromptCacheWarmer(PromptSeeder promptSeeder, RemotePromptRepository remote,
                             PromptProperties props) {
        this.promptSeeder = promptSeeder;
        this.remote = remote;
        this.props = props;
    }

    @PostConstruct
    void warm() {
        if (!props.isEnabled() || !props.isConfigured()) {
            log.info("[prompt] Langfuse 未启用/未配置，跳过启动预热");
            return;
        }
        List<String> keys = promptSeeder.listAllKeys();
        long start = System.currentTimeMillis();
        // 串行预热，避免并发请求被 Langfuse 限流
        long hit = keys.stream()
                .map(remote::fetch)
                .filter(Optional::isPresent)
                .count();
        log.info("[prompt] 启动预热完成 {}/{} keys，耗时 {}ms", hit, keys.size(),
                System.currentTimeMillis() - start);
    }
}
