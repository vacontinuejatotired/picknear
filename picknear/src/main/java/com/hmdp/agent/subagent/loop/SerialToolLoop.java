package com.hmdp.agent.subagent.loop;

import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.subagent.loop.SubAgentToolLoopContext;
import com.hmdp.agent.task.SubTask;
import com.hmdp.agent.util.TextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 原"按轮逐个调用"策略（默认，零行为差异）。
 * <p>
 * prompt 规则强制"每次只调用一个工具"，轮内串行执行 + 串行压缩，
 * 逻辑与原 {@code ToolCallLoopExecutor} 完全一致（仅迁入本策略）。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.tool-loop", havingValue = "serial", matchIfMissing = true)
public class SerialToolLoop extends AbstractToolLoop {

    @Override
    public String toolCallRule() {
        return "每次只调用一个工具，等待返回结果后再调下一个";
    }

    @Override
    protected ToolResponseMessage executeRound(AssistantMessage out, SubAgentToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining,
            AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey) {
        int compressLength = ctx.props().getCompressLength();
        ToolContext toolCtx = new ToolContext(ctx.toolContext() == null ? Map.of() : ctx.toolContext());
        List<ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
            ToolCallback cb = findByName(ctx.callbacks(), tc.name());
            if (cb == null) {
                responses.add(new ToolResponse(tc.id(), tc.name(), "错误：工具不可用"));
                continue;
            }
            // 同工具同参数 = 连续重复标记；数据可能已被工具流之外修改，不抑制，仅记录
            String key = tc.name() + "|" + (tc.arguments() == null ? "" : tc.arguments());
            if (key.equals(lastCallKey.get())) {
                dupCounter.incrementAndGet();
                log.warn("[ToolLoop] 检测到同工具同参数连续重复调用 [tool={}]，不抑制（数据可能已变更）", tc.name());
            } else {
                lastCallKey.set(key);
            }
            try {
                String raw = cb.call(tc.arguments(), toolCtx);
                String compact = compressor.compress(raw, tc.name(), compressLength);
                responses.add(new ToolResponse(tc.id(), tc.name(), compact));
                doneSummary.put(tc.name(), TextUtils.truncate(compact, 50));
            } catch (ConfirmRequiredException e) {
                // 审批信号：冒泡到 TaskPlanner 生成审批记录并暂停（不重试、不入历史）
                throw e;
            } catch (Exception e) {
                // 普通工具异常：压成一行错误入历史，LLM 继续处理（对齐旧行为）
                log.warn("[ToolLoop] 工具执行失败 [tool={}, err={}]", tc.name(), e.getMessage());
                responses.add(new ToolResponse(tc.id(), tc.name(), "错误：" + e.getMessage()));
                doneSummary.put(tc.name(), "执行失败：" + e.getMessage());
            }
            callCounter.incrementAndGet();
            removeExecuted(remaining, tc.name());
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }
}
