package com.hmdp.agent.tool;

import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.guard.ToolGuardManager;
import com.hmdp.agent.observability.api.AgentTracer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
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
    private ToolDefinitionProvider toolDefinitionProvider;

    /** 每轮对话分配一个 conversationId，AiServiceImpl 可在调用前更新 */
    private volatile String conversationId = UUID.randomUUID().toString().replace("-", "");

    public ToolBeanCollector(ToolGuardManager guardManager, AgentTracer agentTracer,
                             ToolDefinitionProvider toolDefinitionProvider) {
        this.guardManager = guardManager;
        this.agentTracer = agentTracer;
        this.toolDefinitionProvider = toolDefinitionProvider;
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
                    // 用守卫包装（工具描述由 toolDefinitionProvider 外置覆盖）
                    GuardedToolCallback guarded = new GuardedToolCallback(
                            raw, guardManager, conversationId, null,
                            /* returnDirect 由 @Tool 上的 returnDirect 决定
                               此处无法直接获取，Spring AI 内部处理，默认 false */
                            false, agentTracer, toolDefinitionProvider
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
