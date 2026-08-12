package com.hmdp.agent.config;

import com.hmdp.agent.routing.ToolIntentTree;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 写操作审批一致性校验（启动 fail-fast）。
 * <p>
 * 保证意图树 WRITE 子树内每个工具都已在 {@code hmdp.prompt-guard.confirm-tools} 名单中，
 * 防止未来新增写工具漏挂审批。守卫层是安全边界，审批决策不从路由层派生，一致性靠本校验兜住。
 * </p>
 */
@Slf4j
@Component
public class WriteGuardConsistencyCheck {

    private final PromptGuardProperties guardProperties;

    public WriteGuardConsistencyCheck(PromptGuardProperties guardProperties) {
        this.guardProperties = guardProperties;
    }

    @PostConstruct
    void validate() {
        List<String> confirmTools = guardProperties.getConfirmTools();
        List<String> missing = ToolIntentTree.writeTools().stream()
                .filter(t -> !confirmTools.contains(t))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "WRITE 子树工具未纳入审批名单 hmdp.prompt-guard.confirm-tools: " + missing
                            + "（写操作必须走用户审批）");
        }
        log.info("写操作审批一致性校验通过：WRITE 子树 {} 全部在 confirm-tools", ToolIntentTree.writeTools());
    }
}
