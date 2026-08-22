package com.hmdp.agent.prompt.seed;

import com.hmdp.agent.prompt.config.PromptProperties;
import com.hmdp.agent.prompt.repo.RemotePromptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提示词管理端点（生产编排用，默认关闭）。
 * <p>
 * 仅当 {@code agent.prompt.seed-enabled=true} 时可用：seed 推模板、reload 清缓存。
 * 注意：seed 会把内置模板覆盖到 Langfuse production label（破坏性），
 * 默认开关关闭即为第一道防线；登录拦截器只校验登录态，不做角色校验。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/agent/prompt")
public class PromptAdminController {

    private final PromptSeeder seeder;
    private final RemotePromptRepository remote;
    private final PromptProperties props;

    public PromptAdminController(PromptSeeder seeder, RemotePromptRepository remote,
                                 PromptProperties props) {
        this.seeder = seeder;
        this.remote = remote;
        this.props = props;
    }

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed() {
        if (!props.isSeedEnabled()) {
            return ResponseEntity.status(403).body(Map.of("error", "种子端点未开启（需 agent.prompt.seed-enabled=true）"));
        }
        int ok = seeder.seedAll();
        remote.evictAll();
        return ResponseEntity.ok(Map.of("seeded", ok, "total", seeder.listAllKeys().size()));
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        if (!props.isSeedEnabled()) {
            return ResponseEntity.status(403).body(Map.of("error", "种子端点未开启（需 agent.prompt.seed-enabled=true）"));
        }
        remote.evictAll();
        return ResponseEntity.ok(Map.of("reloaded", true));
    }
}
