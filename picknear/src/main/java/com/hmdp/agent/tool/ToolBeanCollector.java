package com.hmdp.agent.tool;

import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.guard.ToolGuardManager;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动收集所有标注了 {@link TargetTool @TargetTool} 的 Spring Bean，
 * 并将其转换为 {@link ToolCallback} 数组，每个回调由 {@link GuardedToolCallback}
 * 包装以插入守卫逻辑。
 * <p>
 * 替代手动在 {@code AgentConfig} 中逐个注册，新增工具类时只需：
 * <ol>
 *   <li>在类上标注 {@code @TargetTool}（已含 {@code @Component} 语义）；</li>
 *   <li>在方法上标注 {@code @Tool}。</li>
 * </ol>
 * 无需修改配置代码。
 * <p>
 * 使用示例（AgentConfig）：
 * <pre>{@code
 * .defaultTools(toolBeanCollector.getToolCallbacks())
 * }</pre>
 *
 * @see TargetTool
 * @see GuardedToolCallback
 * @see org.springframework.ai.chat.client.ChatClient.Builder#defaultTools(Object...)
 */
@Slf4j
@Component
public class ToolBeanCollector implements ApplicationContextAware {

    private ToolCallback[] toolCallbacks = new ToolCallback[0];
    private ToolGuardManager guardManager;
    private AgentTracer agentTracer;
    private final PromptGuardProperties promptGuardProperties;
    private ToolDefinitionProvider toolDefinitionProvider;
    /** 实际运行的模型名（从 ChatModel 默认配置解析，编码进 guard span 名） */
    private final String modelName;
    /** 参数脱敏器（guard span 名参数摘要写入前统一出口） */
    private final AttributeSanitizer sanitizer;

    /** 每轮对话分配一个 conversationId，AiServiceImpl 可在调用前更新 */
    private volatile String conversationId = UUID.randomUUID().toString().replace("-", "");

    public ToolBeanCollector(ToolGuardManager guardManager, AgentTracer agentTracer,
                             PromptGuardProperties promptGuardProperties,
                             ToolDefinitionProvider toolDefinitionProvider) {
        this(guardManager, agentTracer, promptGuardProperties, toolDefinitionProvider, null, null);
    }

    /** 主构造：注入 ChatModel 解析实际模型名，用于 guard span 名编码（Langfuse 不展示自定义属性）。
     *  @Autowired 显式标记：本类有多个构造器（4 参便捷版仅测试用），Spring 需以此为准装配。 */
    @Autowired
    public ToolBeanCollector(ToolGuardManager guardManager, AgentTracer agentTracer,
                             PromptGuardProperties promptGuardProperties,
                             ToolDefinitionProvider toolDefinitionProvider,
                             ChatModel chatModel, AttributeSanitizer sanitizer) {
        this.guardManager = guardManager;
        this.agentTracer = agentTracer;
        this.promptGuardProperties = promptGuardProperties;
        this.toolDefinitionProvider = toolDefinitionProvider;
        this.modelName = resolveModelName(chatModel);
        this.sanitizer = sanitizer;
    }

    /** 从 ChatModel 默认配置解析实际模型名；拿不到返回 null（guard span 名省略模型段，Fail-Open） */
    private static String resolveModelName(ChatModel chatModel) {
        try {
            if (chatModel != null && chatModel.getDefaultOptions() != null) {
                String model = chatModel.getDefaultOptions().getModel();
                if (model != null && !model.isBlank()) {
                    return model;
                }
            }
        } catch (Exception e) {
            log.warn("[ToolBeanCollector] 解析模型名失败, guard span 名省略模型段", e);
        }
        return null;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(TargetTool.class);

        List<ToolCallback> collected = new ArrayList<>();
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            TargetTool annotation = resolveAnnotation(bean);

            if (annotation != null && annotation.active()) {
                // 将 Bean 转为 Spring AI 的 ToolCallback 列表（每个 @Tool 方法一个）
                List<ToolCallback> rawCallbacks = List.of(ToolCallbacks.from(bean));
                for (ToolCallback raw : rawCallbacks) {
                    // 用守卫包装（approvalEnabled 由配置决定，工具描述由 toolDefinitionProvider 外置覆盖）
                    GuardedToolCallback guarded = new GuardedToolCallback(
                            raw, guardManager, conversationId, null,
                            /* returnDirect 由 @Tool 上的 returnDirect 决定
                               此处无法直接获取，Spring AI 内部处理，默认 false */
                            false, agentTracer,
                            promptGuardProperties.getApproval().isEnabled(),
                            toolDefinitionProvider,
                            promptGuardProperties.getToolResult().getMaxChars(),
                            modelName, sanitizer
                    );
                    collected.add(guarded);
                    log.info("注册工具 [{}] -> GuardedToolCallback",
                            raw.getToolDefinition().name());
                }
            } else if (annotation != null) {
                log.info("跳过已停用的工具 Bean [{}]: {}", entry.getKey(), bean.getClass().getSimpleName());
            }
        }

        this.toolCallbacks = collected.toArray(new ToolCallback[0]);
        log.info("工具回调收集完成，共 {} 个（激活 {}）", toolCallbacks.length, collected.size());
    }

    /**
     * 返回收集到的所有已包装的 {@link ToolCallback} 数组。
     * <p>
     * 可直接传入 {@code ChatClient.Builder.defaultTools(Object...)}。
     */
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks;
    }

    /**
     * 按工具名取单个已包装的 {@link ToolCallback}（不存在时返回 null）。
     * <p>
     * 审批恢复路径用于定位待确认工具并走 {@link GuardedToolCallback#callBypass}。
     * </p>
     */
    public ToolCallback getToolCallback(String toolName) {
        if (toolName == null || toolCallbacks == null) return null;
        for (ToolCallback cb : toolCallbacks) {
            if (toolName.equals(GuardedToolCallback.rawName(cb))) {
                return cb;
            }
        }
        return null;
    }

    /**
     * 获取当前会话 ID
     */
    public String getConversationId() {
        return conversationId;
    }

    /**
     * 更新会话 ID（新对话开始时由 AiServiceImpl 调用）
     */
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * 解析注解（兼容 CGLIB 代理场景）
     */
    private TargetTool resolveAnnotation(Object bean) {
        TargetTool annotation = bean.getClass().getAnnotation(TargetTool.class);
        if (annotation == null) {
            Class<?> userClass = org.springframework.util.ClassUtils.getUserClass(bean.getClass());
            annotation = userClass.getAnnotation(TargetTool.class);
        }
        return annotation;
    }
}
