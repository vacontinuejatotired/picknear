package com.hmdp.agent.execution;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.plan.model.SubTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具执行状态记录器（评估数据补齐，评测设计文档 §6.1）。
 * <p>
 * 收集一次子 Agent 执行（subagent span）内每个工具的终态（COMPLETED/FAILED），
 * 轮末统一刷入 span 属性 {@code tool.{i}.name / tool.{i}.status}，供 Langfuse 平台
 * 评估器读取。CONFIRM（审批挂起）不记录——执行未发生，安全事件由 guard 层承载。
 * </p>
 * <p>
 * 编号规则：序号为 subagent span 内全局单调递增（reset 归零）；同工具重复调用/重试
 * 不占新号（putIfAbsent），状态覆盖为最新。
 * 线程安全：record 在策略并发线程调用（batch/DAG），CHM + AtomicInteger 原子；
 * flush 在 executeRound 返回后的主线程调用，只读 CHM。
 * Fail-Open：span 缺失或 flush 异常静默，不阻断执行。
 * </p>
 */
@Slf4j
@Component
public class ToolExecutionRecorder {

    private final AtomicInteger nextIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Integer> nameToIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> nameToStatus = new ConcurrentHashMap<>();
    private volatile AgentSpan span;

    /** 绑定 subagent span 并清空收集（execute 入口调用，一次执行一个 span） */
    public void reset(AgentSpan span) {
        this.span = span;
        nextIndex.set(0);
        nameToIndex.clear();
        nameToStatus.clear();
    }

    /** 记录工具终态：同工具重复调用/重试不占新号，状态覆盖为最新 */
    public void record(String toolName, SubTaskStatus status) {
        if (toolName == null || status == null) {
            return;
        }
        nameToIndex.putIfAbsent(toolName, nextIndex.getAndIncrement());
        nameToStatus.put(toolName, status.name());
    }

    /** 把收集结果写入 span 属性（轮末/出口调用，幂等全量写，量小 ≤ maxTotalCalls） */
    public void flush() {
        AgentSpan current = span;
        if (current == null || nameToIndex.isEmpty()) {
            return;
        }
        try {
            nameToIndex.forEach((name, i) -> {
                String status = nameToStatus.get(name);
                if (status != null) {
                    current.set(AgentField.TOOL_ENTRY_NAME, String.valueOf(i), name);
                    current.set(AgentField.TOOL_ENTRY_STATUS, String.valueOf(i), status);
                }
            });
        } catch (Exception e) {
            log.warn("[ToolExecutionRecorder] flush 失败，静默 [err={}]", e.toString());
        }
    }
}
