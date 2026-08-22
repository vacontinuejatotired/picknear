package com.hmdp.agent.prompt.seed;

import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.repo.BuiltinPromptRepository;
import com.hmdp.agent.prompt.repo.RemotePromptRepository;
import com.hmdp.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次性种子：把内置模板推送到 Langfuse（创建/新版本，打 production label）。
 * <p>
 * 工具模板键由 {@link ToolRegistry} 提供（工具定义即事实源，新增工具自动纳入，
 * 无需在此登记）；文本模板键显式枚举。seed 后 Langfuse 成为事实源，
 * UI 直接编辑即可，无需再次 seed（重建会覆盖线上改动，避免用 CommandLineRunner 自动触发）。
 * </p>
 */
@Slf4j
@Component
public class PromptSeeder {

    private final BuiltinPromptRepository builtin;
    private final RemotePromptRepository remote;
    private final ToolRegistry toolRegistry;

    public PromptSeeder(BuiltinPromptRepository builtin, RemotePromptRepository remote,
                        ToolRegistry toolRegistry) {
        this.builtin = builtin;
        this.remote = remote;
        this.toolRegistry = toolRegistry;
    }

    /** 全部模板键（文本 + 工具） */
    public List<String> listAllKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(PromptKeys.SYSTEM_MAIN);
        keys.add(PromptKeys.SYSTEM_SUBAGENT);
        keys.add(PromptKeys.SYSTEM_PLANNER);
        keys.add(PromptKeys.PLANNER_USER);
        keys.add(PromptKeys.SUBAGENT_EXECUTION);
        keys.add(PromptKeys.TASK_MERGE);
        for (String tool : toolRegistry.allToolNames()) {
            keys.add(PromptKeys.tool(tool));
        }
        return keys;
    }

    /** 把内置模板逐个推送到 Langfuse，返回成功数 */
    public int seedAll() {
        int ok = 0;
        for (String key : listAllKeys()) {
            String content = builtin.load(key).orElse(null);
            if (content == null) {
                log.warn("[seed] 跳过缺失模板: {}", key);
                continue;
            }
            if (remote.seed(key, content)) {
                ok++;
            }
        }
        log.info("[seed] 完成 {}/{} 个模板推送", ok, listAllKeys().size());
        return ok;
    }
}
