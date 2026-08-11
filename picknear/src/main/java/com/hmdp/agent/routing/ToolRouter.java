package com.hmdp.agent.routing;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.task.TaskReport;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 规划工具路由门面。
 * <p>
 * {@link #buildCatalog(boolean, ToolCallback[], TaskReport)}：
 * compact=true 走紧凑目录（CompactCatalogBuilder），否则全量目录（名字+完整注解描述，原 askAiForPlan 逻辑）；
 * {@link #isUncertain(String)}：检测规划 LLM 输出的 {@link #UNCERTAIN_MARKER}（保底触发信号）。
 * </p>
 */
@Component
public class ToolRouter {

    /** 识别不出匹配工具时规划 LLM 输出的标记 */
    public static final String UNCERTAIN_MARKER = "__UNCERTAIN__";

    @Resource
    private CompactCatalogBuilder compactCatalogBuilder;

    @Resource
    private FeatureProperties featureProperties;

    /**
     * 构建工具目录文本。compact=true 用紧凑目录（标签+参数名，且按用户输入过滤相关工具），
     * 否则全量（名字+完整描述）。
     */
    public String buildCatalog(boolean compact, ToolCallback[] callbacks, TaskReport history, String userInput) {
        if (compact) {
            return compactCatalogBuilder.build(callbacks, history, maxTagLength(), userInput);
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCallback cb : callbacks) {
            String name = GuardedToolCallback.rawName(cb);
            if (history.isCompleted(name) || history.isFinalFailed(name)) continue;
            sb.append("- ").append(name).append(": ").append(GuardedToolCallback.rawDescription(cb)).append("\n");
        }
        return sb.toString();
    }

    /** 规划结果是否含不确定标记（保底触发信号） */
    public boolean isUncertain(String rawPlan) {
        return rawPlan != null && rawPlan.contains(UNCERTAIN_MARKER);
    }

    /** 紧凑标签长度上限（Fail-Open：配置缺失时用默认 60） */
    private int maxTagLength() {
        if (featureProperties == null || featureProperties.getToolRouting() == null) {
            return 60;
        }
        return featureProperties.getToolRouting().getMaxTagLength();
    }
}
