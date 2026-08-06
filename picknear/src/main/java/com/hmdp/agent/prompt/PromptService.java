package com.hmdp.agent.prompt;

import com.hmdp.agent.prompt.model.ResolvedToolPrompt;

import java.util.Map;
import java.util.Optional;

/**
 * 提示词服务门面：渲染 LLM 提示词，Langfuse 失败时降级内置模板（Fail-Open）。
 */
public interface PromptService {

    /**
     * 渲染文本模板并注入变量。
     *
     * @param promptKey 模板键（Langfuse Prompt 名 / 内置资源文件名）
     * @param vars      {@code {{var}}} 占位符替换值，可为 null
     * @return 渲染后文本；模板缺失/渲染异常时返回内置兜底（绝不抛异常）
     */
    String render(String promptKey, Map<String, String> vars);

    /**
     * 渲染工具描述模板（JSON 结构）。
     *
     * @param toolKey 工具模板键（如 {@code agent.tool.queryWeather}）
     * @param vars    预留变量（当前无占位）
     * @return 解析后的工具描述；远程/内置都取不到时 empty（调用方回退 {@code @Tool} 注解）
     */
    Optional<ResolvedToolPrompt> renderTool(String toolKey, Map<String, String> vars);
}
