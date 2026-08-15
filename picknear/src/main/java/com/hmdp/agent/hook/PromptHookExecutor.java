package com.hmdp.agent.hook;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

/**
 * Prompt Hook 链执行器 — 统一 JSON / SSE 双模的 Hook 链执行与决策处理。
 * <p>
 * 双模入口（{@code chatReturnStringResult} / {@code chatWithToolcall}）此前各自重复
 * 「构建 ChatContext → 执行 Hook 链（含观测）→ 处理决策（BLOCK/REPLACE/PASS）」三段逻辑，
 * 收敛到这里后行为一致、职责单一。输出 {@link HookOutcome}：未阻断时携带最终输入与
 * ChatContext（SSE 后处理段仍需使用），阻断时携带原因。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptHookExecutor {

    private final PromptHookChain promptHookChain;
    private final ChatMemory chatMemory;
    private final AgentTracer agentTracer;

    /**
     * 执行 Hook 链并处理决策。
     *
     * @param content        原始用户输入
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID（AgentContext 未设置时的兜底；入口已设置则忽略）
     * @param rootSpan       会话根 span（AgentContext 未设置时的兜底；入口已设置则忽略）
     */
    public HookOutcome execute(String content, String conversationId, Long userId, AgentSpan rootSpan) {
        // 1. 构造 Hook 上下文（数据来源统一为 AgentContext：入口创建、异步边界 Propagator 传播）。
        //    AgentContext 未设置时回退显式参数（直调/测试路径，Fail-Open），行为与旧手拼一致。
        AgentContext agentCtx = AgentContextHolder.get();
        ChatContext ctx;
        if (agentCtx != null) {
            ctx = ChatContext.from(agentCtx);
        } else {
            ctx = ChatContext.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .originalContent(content)
                    .history(chatMemory.get(conversationId))
                    .build();
            ctx.setRootSpan(rootSpan);
        }

        // 2. 执行 Hook 链（观测：agent.prompt_hook，链执行后结束）
        HookResult hookResult;
        try (AgentSpan hookSpan = agentTracer.start(AgentSpanSpec.PROMPT_HOOK, null)) {
            hookResult = promptHookChain.execute(content, ctx);
            hookSpan.set(AgentField.HOOK_DECISION, String.valueOf(hookResult.getDecision()));
            if (hookResult.getHookName() != null) {
                hookSpan.set(AgentField.HOOK_NAME, hookResult.getHookName());
            }
        }

        // 3. 处理决策
        String finalContent = processHookResult(hookResult, content, conversationId);
        if (finalContent == null) {
            log.warn("Prompt 被拦截 [reason={}, hook={}]", hookResult.getReason(), hookResult.getHookName());
            return HookOutcome.blocked(ctx, hookResult.getReason());
        }
        return HookOutcome.passed(ctx, finalContent);
    }

    /**
     * 处理 Hook 链的决策结果
     *
     * @return 替换后的文本（可用于 LLM 调用），若 BLOCK 则返回 null
     */
    private String processHookResult(HookResult result, String content, String conversationId) {
        switch (result.getDecision()) {
            case BLOCK -> {
                return null;
            }
            case REPLACE -> {
                log.info("Prompt 被替换 [hook={}]", result.getHookName());
                // 如果有清洗后的历史，替换 ChatMemory 中的内容
                if (result.getReplacedHistory() != null) {
                    chatMemory.clear(conversationId);
                    chatMemory.add(conversationId, result.getReplacedHistory());
                    log.info("对话历史已清洗 [conversationId={}]", conversationId);
                }
                return result.getReplacedText();
            }
            case PASS -> {
                return content;
            }
            default -> {
                return content;
            }
        }
    }

    /**
     * Hook 链执行结果：未阻断（passed）携带最终 LLM 输入与 ChatContext；
     * 阻断（blocked）携带原因，调用方直接转错误响应。
     */
    public record HookOutcome(ChatContext ctx, String finalContent, String blockReason) {

        public static HookOutcome passed(ChatContext ctx, String finalContent) {
            return new HookOutcome(ctx, finalContent, null);
        }

        public static HookOutcome blocked(ChatContext ctx, String blockReason) {
            return new HookOutcome(ctx, null, blockReason);
        }

        public boolean blocked() {
            return blockReason != null;
        }
    }
}
