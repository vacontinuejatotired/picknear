package com.hmdp.agent.prompt.repo;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BuiltinPromptRepository — 内置模板仓库测试。
 * <p>
 * 校验 classpath 下 13 个模板文件可读、缺失 key 返回 empty。
 * </p>
 */
class BuiltinPromptRepositoryTest {

    private final BuiltinPromptRepository repo = new BuiltinPromptRepository();

    @Test
    void should_load_existing_text_template() {
        Optional<String> content = repo.load("agent.system.main");
        assertThat(content).as("主系统提示词应可读取").isPresent();
        assertThat(content.get()).contains("智能助手");
    }

    @Test
    void should_load_all_thirteen_keys() {
        String[] keys = {
                "agent.system.main", "agent.system.subagent", "agent.system.planner",
                "agent.prompt.planner", "agent.prompt.subagent.execution", "agent.prompt.task.merge",
                "agent.tool.queryPublishedBlogs", "agent.tool.publishTestBlog", "agent.tool.queryBlogsByTitle",
                "agent.tool.queryWeather", "agent.tool.queryTotalBlogs", "agent.tool.queryTotalUsers",
                "agent.tool.queryTotalShops"
        };
        for (String key : keys) {
            assertThat(repo.load(key))
                    .as("内置模板应存在: " + key)
                    .isPresent();
        }
    }

    @Test
    void should_return_empty_for_missing_key() {
        assertThat(repo.load("nonexistent.key")).as("缺失 key 应返回 empty").isEmpty();
    }

    @Test
    void should_parse_tool_description_json() {
        Optional<String> content = repo.load("agent.tool.queryWeather");
        assertThat(content).isPresent();
        // 工具描述模板应为可解析 JSON（含 description + params）
        assertThat(content.get()).contains("\"description\"").contains("\"params\"");
    }
}
