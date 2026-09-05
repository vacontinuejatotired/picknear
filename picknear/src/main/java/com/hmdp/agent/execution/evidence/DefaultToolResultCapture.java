package com.hmdp.agent.execution.evidence;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.execution.model.ToolEvidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ToolResultCapture} 默认实现 —— 累加器状态放 {@link AgentContext.attributes}。
 * <p>
 * 状态载体选择：证据需在"编排主线程 begin/snapshot、工具执行线程（含并行策略子线程）capture"之间
 * 共享；AgentContext 已由 {@code AgentContextPropagator} 跨线程传播且 attributes 为
 * ConcurrentHashMap —— 以其内一个 {@link Collections#synchronizedList} 作累加器，天然线程安全。
 * </p>
 * <p>
 * fail-open：上下文缺失（无 AgentContext 链路）时 capture 静默丢弃（debug 日志），snapshot 返回空，
 * 不阻断主链——证据是旁路增强，不该成为执行副作用。
 * </p>
 */
@Slf4j
@Component
public class DefaultToolResultCapture implements ToolResultCapture {

    /** AgentContext.attributes 中的轮级证据累加器 key（每轮 begin 覆写，防跨轮残留） */
    private static final String ATTR_ROUND_EVIDENCE = "roundToolEvidence";

    @Override
    public void begin() {
        AgentContext ctx = AgentContextHolder.get();
        if (ctx == null) {
            return;
        }
        ctx.putAttribute(ATTR_ROUND_EVIDENCE, newEvidenceList());
    }

    @Override
    public void capture(String toolName, String raw) {
        if (toolName == null || raw == null || raw.isBlank()) {
            return;
        }
        AgentContext ctx = AgentContextHolder.get();
        if (ctx == null) {
            log.debug("[ToolEvidence] 无 AgentContext，证据丢弃 tool={}", toolName);
            return;
        }
        // 懒建兜底：begin 之前即有工具执行（如异常提前路径）也不丢证据
        List<ToolEvidence> list = evidenceList(ctx);
        if (list == null) {
            list = newEvidenceList();
            ctx.attributes().putIfAbsent(ATTR_ROUND_EVIDENCE, list);
            list = evidenceList(ctx);
        }
        if (list != null) {
            list.add(ToolEvidence.inline(toolName, raw));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ToolEvidence> snapshot() {
        AgentContext ctx = AgentContextHolder.get();
        if (ctx == null) {
            return List.of();
        }
        Object removed = ctx.attributes().remove(ATTR_ROUND_EVIDENCE);
        if (!(removed instanceof List<?> list)) {
            return List.of();
        }
        return List.copyOf((List<ToolEvidence>) list);
    }

    @SuppressWarnings("unchecked")
    private List<ToolEvidence> evidenceList(AgentContext ctx) {
        Object attr = ctx.attribute(ATTR_ROUND_EVIDENCE);
        return attr instanceof List ? (List<ToolEvidence>) attr : null;
    }

    private List<ToolEvidence> newEvidenceList() {
        return Collections.synchronizedList(new ArrayList<>());
    }
}
