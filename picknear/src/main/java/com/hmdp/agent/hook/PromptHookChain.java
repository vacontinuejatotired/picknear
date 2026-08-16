package com.hmdp.agent.hook
;

import com.hmdp.agent.context.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt Hook 链式执行器
 * <p>
 * 自动收集所有 {@link PromptHook} Bean，按序串行执行。
 * </p>
 *
 * <h3>执行规则</h3>
 * <ol>
 *   <li><b>PASS</b> — 跳过，currentInput 不变，继续执行下一个 Hook</li>
 *   <li><b>REPLACE</b> — 替换 currentInput，替换结果成为后续 Hook 的输入</li>
 *   <li><b>BLOCK</b> — 立即短路，返回该 BLOCK，后续 Hook 不再执行</li>
 *   <li><b>异常</b> — Fail-Open：捕获异常，降级为 PASS，不影响业务</li>
 * </ol>
 */
@Slf4j
@Component
public class PromptHookChain {

    private final List<PromptHook> hooks;

    public PromptHookChain(List<PromptHook> hooks) {
        this.hooks = hooks;
        log.info("PromptHookChain 初始化完成，共 {} 个 Hook", hooks.size());
        for (PromptHook hook : hooks) {
            log.debug("  注册 Hook [{}]", hook.hookName());
        }
    }

    /**
     * 串行执行所有 Hook。
     *
     * @param originalInput 用户原始输入（不可变）
     * @param context       AgentContext
     * @return 最终决策结果
     */
    public HookResult execute(String originalInput, AgentContext context) {
        if (hooks.isEmpty()) {
            return HookResult.pass();
        }

        String currentInput = originalInput;
        HookResult lastReplace = null;

        for (PromptHook hook : hooks) {
            HookResult result;
            try {
                log.info("HOOK[{}]正在准备执行",hook.hookName());
                result = hook.beforePrompt(originalInput, currentInput, context);

            } catch (Exception e) {
                log.error("Hook [{}] 执行异常，已降级为 PASS", hook.hookName(), e);
                continue; // Fail-Open
            }

            log.debug("Hook [{}] 返回 {} (currentInput={})", hook.hookName(), result.getDecision(),
                    truncate(currentInput, 50));

            switch (result.getDecision()) {
                case BLOCK -> {
                    log.info("Prompt 被 Hook [{}] 拦截: {}", result.getHookName(), result.getReason());
                    return result;
                }
                case REPLACE -> {
                    currentInput = result.getReplacedText();
                    lastReplace = result;
                }
                case PASS -> {} // continue
            }
        }

        // 所有 Hook 执行完毕无 BLOCK
        if (lastReplace != null) {
            return lastReplace;
        }
        return HookResult.pass();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
