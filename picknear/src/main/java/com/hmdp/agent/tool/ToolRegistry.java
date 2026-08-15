package com.hmdp.agent.tool;

import com.hmdp.agent.annotation.ToolMeta;
import com.hmdp.agent.guard.GuardedToolCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表 — 工具清单与业务元数据的单一事实源。
 * <p>
 * 启动后（懒构建）从两个来源聚合：
 * <ul>
 *   <li><b>工具名</b>：复用 {@link ToolBeanCollector} 已收集的 ToolCallback 清单
 *       （工具定义本身，新增工具自动纳入，无需登记）；</li>
 *   <li><b>业务元数据</b>：扫描 {@link ToolMeta @ToolMeta} 注解的工具类
 *       （触发关键词、意图树节点归属，替代原有 3 处硬编码注册表）。</li>
 * </ul>
 * 新增工具 = 新建工具类 + 标 {@code @ToolMeta}，其余消费方（紧凑目录过滤、
 * 意图树归属、提示词种子）自动感知。
 * </p>
 */
@Slf4j
@Component
public class ToolRegistry implements ApplicationContextAware {

    private final ToolBeanCollector toolBeanCollector;
    private ApplicationContext applicationContext;

    private volatile boolean initialized = false;
    private Set<String> allToolNames = Set.of();
    private Map<String, List<String>> keywordsByTool = Map.of();
    private Map<String, Set<String>> intentsByTool = Map.of();

    public ToolRegistry(ToolBeanCollector toolBeanCollector) {
        this.toolBeanCollector = toolBeanCollector;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /** 全部已注册工具名（提示词种子等场景用） */
    public Set<String> allToolNames() {
        ensureInitialized();
        return allToolNames;
    }

    /** 工具的触发关键词（紧凑目录过滤）；未登记返回空列表（保守放行） */
    public List<String> keywordsOf(String toolName) {
        ensureInitialized();
        return keywordsByTool.getOrDefault(toolName, List.of());
    }

    /** 工具的意图树归属节点 id 集合（跨组工具返回多节点）；未登记返回空集合 */
    public Set<String> intentsOf(String toolName) {
        ensureInitialized();
        return intentsByTool.getOrDefault(toolName, Set.of());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (!initialized) {
                build();
                initialized = true;
            }
        }
    }

    private void build() {
        // 1. 工具名清单：直接取自 ToolBeanCollector 收集结果（工具定义即事实源）
        Set<String> names = new LinkedHashSet<>();
        for (ToolCallback cb : toolBeanCollector.getToolCallbacks()) {
            names.add(GuardedToolCallback.rawName(cb));
        }
        this.allToolNames = names;

        // 2. 业务元数据：扫描 @ToolMeta 注解的工具类（类级元数据应用到该类全部工具方法）
        Map<String, List<String>> keywords = new HashMap<>();
        Map<String, Set<String>> intents = new HashMap<>();
        for (Object bean : applicationContext.getBeansWithAnnotation(ToolMeta.class).values()) {
            Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
            ToolMeta meta = userClass.getAnnotation(ToolMeta.class);
            if (meta == null) {
                continue;
            }
            List<String> kw = List.of(meta.keywords());
            Set<String> intentSet = Set.of(meta.intents());
            for (ToolCallback cb : ToolCallbacks.from(bean)) {
                String name = cb.getToolDefinition().name();
                keywords.put(name, kw);
                intents.put(name, intentSet);
                log.debug("[ToolRegistry] 登记工具 [{}] keywords={} intents={}", name, kw, intentSet);
            }
        }
        this.keywordsByTool = keywords;
        this.intentsByTool = intents;
        log.info("[ToolRegistry] 工具注册表构建完成：{} 个工具，{} 个带元数据",
                allToolNames.size(), keywords.size());
    }
}
