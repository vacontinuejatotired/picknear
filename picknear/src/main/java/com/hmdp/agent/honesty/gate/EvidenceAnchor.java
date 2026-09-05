package com.hmdp.agent.honesty.gate;

import com.hmdp.agent.execution.model.ToolEvidence;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 断言锚定证据集合（反编造 L3，值对象）——summary 断言允许被锚定的来源。
 * <p>
 * P0：evidence = 本轮工具真值（L0 捕获，模型可见超集）；userInput = 用户原话；
 * ledgerText 与白名单由 L4/P1 填充（本对象预留字段，先给空集）。
 * </p>
 */
public record EvidenceAnchor(List<ToolEvidence> evidence, String userInput,
                             String ledgerText, Set<String> whitelistTokens) {

    public static EvidenceAnchor of(List<ToolEvidence> evidence, String userInput) {
        return new EvidenceAnchor(evidence == null ? List.of() : evidence,
                userInput == null ? "" : userInput,
                "",
                Collections.emptySet());
    }

    /** 本轮是否执行过任一统计工具（Detector A 锚定依据：统计断言须有统计工具证据） */
    public boolean hasAnyStatsTool() {
        for (ToolEvidence e : evidence) {
            if (e != null && e.toolName() != null && EvidenceAssertionGate.STATS_TOOLS.contains(e.toolName())) {
                return true;
            }
        }
        return false;
    }
}
