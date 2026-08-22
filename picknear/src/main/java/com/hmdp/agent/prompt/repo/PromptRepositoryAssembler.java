package com.hmdp.agent.prompt.repo;

import com.hmdp.agent.prompt.config.PromptProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 远端提示词仓库装配器（普通类，工厂接口化属 YAGNI，评审 13.2.3）。
 * <p>
 * 按 {@code agent.prompt.repository.type} 选择实现：
 * <ul>
 *   <li>{@code langfuse}（默认）→ {@link LangfusePromptRepository}（兼容现状 Fail-Open 语义）</li>
 *   <li>{@code none} → {@link NoopPromptRepository#INSTANCE}（显式无远程，走内置模板）</li>
 *   <li>缺省（未配 type）→ langfuse（与现状一致；若 base-url 为空则 Langfuse 内部 Fail-Open）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class PromptRepositoryAssembler {

    private static final String DEFAULT_TYPE = "langfuse";

    private final LangfusePromptRepository langfuse;
    private final PromptProperties props;

    public PromptRepositoryAssembler(LangfusePromptRepository langfuse, PromptProperties props) {
        this.langfuse = langfuse;
        this.props = props;
    }

    /**
     * 装配远端提示词仓库：未识别 type / 缺参数 / base-url 空 → Noop（Fail-Open，不抛异常）。
     */
    public RemotePromptRepository assemble() {
        String type = (props.getRepository() != null && props.getRepository().getType() != null)
                ? props.getRepository().getType().trim().toLowerCase()
                : DEFAULT_TYPE;
        if ("none".equals(type)) {
            log.info("[prompt] repository.type=none，远程提示词仓库关闭（Fail-Open，走内置模板）");
            return NoopPromptRepository.INSTANCE;
        }
        if (DEFAULT_TYPE.equals(type) || "langfuse".equals(type)) {
            return langfuse;
        }
        log.warn("[prompt] 未知 repository.type={}，降级 NoopPromptRepository（Fail-Open）", type);
        return NoopPromptRepository.INSTANCE;
    }
}