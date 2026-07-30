package com.hmdp.agent.hook
;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Prompt Hook 的决策结果
 * <p>
 * 由 {@link PromptHook#beforePrompt(String, String, ChatContext)} 返回，
 * {@link PromptHookChain} 根据所有 Hook 的结果聚合出最终决策。
 * </p>
 *
 * <pre>
 * 决策语义：
 * - PASS     —— 放行，不修改任何内容
 * - BLOCK    —— 拦截，携带阻断原因（短路，后续 Hook 不再执行）
 * - REPLACE  —— 替换当前输入文本，可选携带清洗后的历史列表
 * - PLANNING —— 需要进入任务规划（仅 {@code AfterAiHook} 使用）
 * </pre>
 */
public class HookResult {

    public enum Decision {
        PASS,
        BLOCK,
        REPLACE,
        PLANNING
    }

    private final Decision decision;
    private final String reason;
    private final String replacedText;
    private final List<Message> replacedHistory;
    private final String hookName;

    private HookResult(Decision decision, String reason, String replacedText,
                      List<Message> replacedHistory, String hookName) {
        this.decision = decision;
        this.reason = reason;
        this.replacedText = replacedText;
        this.replacedHistory = replacedHistory;
        this.hookName = hookName;
    }

    // ---- getters ----

    public Decision getDecision() { return decision; }
    public String getReason() { return reason; }
    public String getReplacedText() { return replacedText; }
    public List<Message> getReplacedHistory() { return replacedHistory; }
    public String getHookName() { return hookName; }

    // ---- 便捷判断 ----

    public boolean isPass() { return decision == Decision.PASS; }
    public boolean isBlock() { return decision == Decision.BLOCK; }
    public boolean isReplace() { return decision == Decision.REPLACE; }
    public boolean isPlanning() { return decision == Decision.PLANNING; }

    // ---- 工厂方法 ----

    /** 放行 */
    public static HookResult pass() {
        return new HookResult(Decision.PASS, null, null, null, null);
    }

    /** 拦截 */
    public static HookResult block(String reason, String hookName) {
        return new HookResult(Decision.BLOCK, reason, null, null, hookName);
    }

    /** 替换当前输入文本 */
    public static HookResult replace(String replacedText, String hookName) {
        return new HookResult(Decision.REPLACE, null, replacedText, null, hookName);
    }

    /** 替换当前输入文本 + 清洗后的历史列表 */
    public static HookResult replaceWithHistory(String replacedText,
                                                List<Message> replacedHistory,
                                                String hookName) {
        return new HookResult(Decision.REPLACE, null, replacedText, replacedHistory, hookName);
    }

    /** 需要进入任务规划（AfterAiHook 专用） */
    public static HookResult planningRequired() {
        return new HookResult(Decision.PLANNING, null, null, null, null);
    }
}
