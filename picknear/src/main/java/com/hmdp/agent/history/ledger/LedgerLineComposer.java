package com.hmdp.agent.history.ledger;

import com.hmdp.agent.execution.model.ToolEvidence;

/**
 * 事实账本行合成器（反编造 L4，纯函数）——把一条工具真值证据压成单行短文本。
 */
public final class LedgerLineComposer {

    /** 单行原始内容上限（超出截断），避免账本行过长灌爆上下文 */
    static final int MAX_LINE_CHARS = 200;

    private LedgerLineComposer() {
    }

    public static String compose(ToolEvidence evidence) {
        if (evidence == null || evidence.toolName() == null) {
            return null;
        }
        String raw = evidence.raw() == null ? "" : evidence.raw();
        return evidence.toolName() + ": " + truncate(collapse(raw));
    }

    /** 把多行原始文本压成单行（换行→空格），便于账本一行一条 */
    private static String collapse(String text) {
        return text.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_LINE_CHARS ? text : text.substring(0, MAX_LINE_CHARS) + "…";
    }
}
