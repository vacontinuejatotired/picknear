package com.hmdp.agent.prompt.impl;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.prompt.PromptRenderer;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.prompt.config.PromptProperties;
import com.hmdp.agent.prompt.model.ResolvedToolPrompt;
import com.hmdp.agent.prompt.repo.BuiltinPromptRepository;
import com.hmdp.agent.prompt.repo.LocalPromptRepository;
import com.hmdp.agent.prompt.repo.RemotePromptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 提示词服务默认实现：Langfuse → 本地数据库 → 内置 → Fail-Open，统一埋 {@code agent.prompt.{key}} span。
 * <p>
 * 编排顺序：总开关 enabled / 工具开关 toolDescriptionEnabled 关闭或未配置远程 → 直接内置；
 * 远程取到 → 远程；远程 404/失败 → 本地数据库；本地数据库也没有 → 内置。渲染用 {@link PromptRenderer}，永不抛异常。
 * </p>
 */
@Slf4j
@Component
public class DefaultPromptService implements PromptService {

    private final PromptProperties props;
    private final RemotePromptRepository remote;
    private final LocalPromptRepository local;
    private final BuiltinPromptRepository builtin;
    private final AgentTracer agentTracer;

    public DefaultPromptService(PromptProperties props, RemotePromptRepository remote,
                                LocalPromptRepository local, BuiltinPromptRepository builtin,
                                AgentTracer agentTracer) {
        this.props = props;
        this.remote = remote;
        this.local = local;
        this.builtin = builtin;
        this.agentTracer = agentTracer;
    }

    @Override
    public String render(String promptKey, Map<String, String> vars) {
        try (AgentSpan span = agentTracer.start(AgentSpanSpec.PROMPT, promptKey)) {
            String source;
            String template;
            if (props.isEnabled() && props.isConfigured()) {
                Optional<String> r = remote.fetch(promptKey);
                if (r.isPresent()) {
                    template = r.get();
                    source = "remote";
                } else {
                    // 远程失败 → 本地数据库兜底
                    Optional<String> localPrompt = local.fetch(promptKey, props.getDefaultLabel());
                    if (localPrompt.isPresent()) {
                        template = localPrompt.get();
                        source = "local";
                    } else {
                        template = builtin.load(promptKey).orElse("");
                        source = "builtin";
                    }
                }
            } else {
                template = builtin.load(promptKey).orElse("");
                source = "builtin";
            }
            String rendered = PromptRenderer.render(template, vars);
            span.set(AgentField.PROMPT_SOURCE, source);
            span.set(AgentField.PROMPT_RENDERED_LEN, String.valueOf(rendered.length()));
            return rendered;
        }
    }

    @Override
    public Optional<ResolvedToolPrompt> renderTool(String toolKey, Map<String, String> vars) {
        try (AgentSpan span = agentTracer.start(AgentSpanSpec.PROMPT, toolKey)) {
            Optional<String> template;
            String source;
            if (props.isToolDescriptionEnabled() && props.isConfigured()) {
                Optional<String> r = remote.fetch(toolKey);
                if (r.isPresent()) {
                    template = r;
                    source = "remote";
                } else {
                    // 远程失败 → 本地数据库兜底
                    Optional<String> localPrompt = local.fetch(toolKey, props.getDefaultLabel());
                    if (localPrompt.isPresent()) {
                        template = localPrompt;
                        source = "local";
                    } else {
                        template = builtin.load(toolKey);
                        source = "builtin";
                    }
                }
            } else {
                template = builtin.load(toolKey);
                source = "builtin";
            }
            if (template.isEmpty()) {
                log.warn("[prompt] 工具描述模板缺失（远程+本地+内置都没有）toolKey={}", toolKey);
                span.set(AgentField.PROMPT_SOURCE, "missing");
                return Optional.empty();
            }
            span.set(AgentField.PROMPT_SOURCE, source);
            return ResolvedToolPrompt.parse(template.get());
        }
    }
}
