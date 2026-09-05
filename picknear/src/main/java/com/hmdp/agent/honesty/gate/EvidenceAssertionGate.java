package com.hmdp.agent.honesty.gate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 输出断言闸（反编造 L3，通道二）——每轮 summary 离开子 Agent 前的确定性断言。
 * <p>
 * P0 内置高精度窄规则 Detector A：summary 含"共/当前共有 N 篇|家|位|条…"统计断言，
 * 而本轮 toolEvidence 没有任何统计工具证据 → 命中（疑似编造平台统计数，T1）。
 * </p>
 * <p>
 * 处置（HonorConfig，默认 OBSERVE）：命中率校准期只观测不改回复；校准后可开
 * {@code APPEND_DISCLAIMER}（附注不重答）。{@code RECHECK}/{@code DROP} 为 P1/P2 演进档，
 * 本实现先按 OBSERVE 语义放行（记日志），不实现重答/丢弃。
 * </p>
 * <p>
 * fail-open：本闸任何异常 → 原样返回 summary，只升日志，绝不阻断主链。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceAssertionGate {

    /** 统计工具白名单（Detector A）：三个平台计数工具 */
    public static final Set<String> STATS_TOOLS =
            Set.of("queryTotalBlogs", "queryTotalShops", "queryTotalUsers");

    private static final String DISCLAIMER_SUFFIX = "（注：以上统计数据未能从本次查询核实，请以实际为准）";

    private final ClaimExtractor claimExtractor;

    /**
     * 对 summary 执行断言并返回处置后文本。
     *
     * @param summary 子 Agent 最终自然语言产出（待校验）
     * @param anchor  锚定证据集合（本轮 toolEvidence + 用户原话 + 账本）
     * @param config  处置档位
     * @return 处置后的 summary（未命中 / OFF / OBSERVE 时原样返回）
     */
    public String gate(String summary, EvidenceAnchor anchor, HonorConfig config) {
        try {
            if (summary == null || summary.isBlank() || anchor == null
                    || config == null || config.action() == HonorAction.OFF) {
                return summary;
            }
            List<Claim> statsClaims = claimExtractor.extract(summary);
            if (statsClaims.isEmpty() || anchor.hasAnyStatsTool()) {
                return summary;
            }
            // Detector A 命中：存在统计断言但本轮无任何统计工具证据
            log.warn("[AssertionGate] Detector A 命中：统计断言无工具证据 summary={}, claims={}",
                    summary, statsClaims);
            switch (config.action()) {
                case APPEND_DISCLAIMER -> {
                    return summary.contains(DISCLAIMER_SUFFIX)
                            ? summary
                            : summary + DISCLAIMER_SUFFIX;
                }
                case OBSERVE, OFF, RECHECK, DROP -> {
                    // RECHECK/DROP 为 P1 演进档，先按观测放行，不重答/不丢弃
                    return summary;
                }
                default -> {
                    return summary;
                }
            }
        } catch (Exception e) {
            // fail-open：闸自身异常绝不阻断主链
            log.warn("[AssertionGate] 断言闸异常，原样放行 summary", e);
            return summary;
        }
    }
}
