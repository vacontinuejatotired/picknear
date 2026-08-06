package com.hmdp.agent.prompt.seed;

import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.repo.BuiltinPromptRepository;
import com.hmdp.agent.prompt.repo.LangfusePromptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次性种子：把内置模板推送到 Langfuse（创建/新版本，打 production label）。
 * <p>
 * 显式枚举全部模板键（6 文本 + 7 工具）。seed 后 Langfuse 成为事实源，
 * UI 直接编辑即可，无需再次 seed（重建会覆盖线上改动，避免用 CommandLineRunner 自动触发）。
 * </p>
 */
@Slf4j
@Component
public class PromptSeeder {

    /** 全部工具名（对应内置模板 agent.tool.{name}.txt） */
    public static final List<String> TOOL_NAMES = List.of(
            "queryPublishedBlogs", "publishTestBlog", "queryBlogsByTitle",
            "queryWeather", "queryTotalBlogs", "queryTotalUsers", "queryTotalShops");

    private final BuiltinPromptRepository builtin;
    private final LangfusePromptRepository remote;

    public PromptSeeder(BuiltinPromptRepository builtin, LangfusePromptRepository remote) {
        this.builtin = builtin;
        this.remote = remote;
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
        for (String tool : TOOL_NAMES) {
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
