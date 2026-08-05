package com.hmdp.agent.hook
;

/**
 * Prompt 钩子接口
 * <p>
 * 在用户输入发送给 LLM 之前执行，可用于安全检测、文本脱敏、指令增强等。
 * 所有实现类需标注 {@link org.springframework.stereotype.Component @Component}，
 * 由 {@link PromptHookChain} 自动收集并串行执行。
 * </p>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>originalInput</b> — 用户原始输入，<strong>不可变</strong>。安全检测类 Hook 应始终基于此值做判断，避免被前置 Hook 的 REPLACE 干扰</li>
 *   <li><b>currentInput</b> — 当前有效输入，已被前置 Hook 修改。增强/追加类 Hook 在此之上操作</li>
 *   <li><b>context</b> — 强类型上下文，包含 userId、conversationId、全量对话历史</li>
 * </ul>
 *
 * <h3>返回语义</h3>
 * <ul>
 *   <li>{@link HookResult#pass()} — 放行，不修改</li>
 *   <li>{@link HookResult#block(String, String)} — 拦截，链路立即短路</li>
 *   <li>{@link HookResult#replace(String, String)} — 替换 currentInput，后续 Hook 在替换后的文本上继续</li>
 * </ul>
 *
 * @see PromptHookChain
 * @see HookResult
 * @see ChatContext
 */
@FunctionalInterface
public interface PromptHook {

    /** Hook 名称（用于日志和错误追踪） */
    default String hookName() { return getClass().getSimpleName(); }

    /**
     * 在用户输入发送给 LLM 前执行。
     *
     * @param originalInput 用户原始输入（不可变，安全检测用此值）
     * @param currentInput  当前有效输入（已被前置 Hook 修改）
     * @param context       ChatContext（userId、conversationId、history）
     * @return HookResult（PASS / BLOCK / REPLACE）
     */
    HookResult beforePrompt(String originalInput, String currentInput, ChatContext context);
}
