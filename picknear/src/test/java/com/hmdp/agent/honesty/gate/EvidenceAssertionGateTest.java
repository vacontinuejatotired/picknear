package com.hmdp.agent.honesty.gate;

import com.hmdp.agent.execution.model.ToolEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvidenceAssertionGate — Detector A 断言闸单测。
 * <p>
 * 覆盖：无统计证据命中（OBSERVE 原样 / APPEND 附注）、有统计证据放行、OFF 恒放行、无统计断言放行。
 * </p>
 */
class EvidenceAssertionGateTest {

    private static final String STATS_SUMMARY = "平台当前共有 120 家店，服务很好。";

    private final EvidenceAssertionGate gate = new EvidenceAssertionGate(new NumericClaimExtractor());

    @Test
    void should_detect_stats_claim_without_evidence_observe() {
        String out = gate.gate(STATS_SUMMARY, EvidenceAnchor.of(List.of(), "统计店数"), HonorConfig.observe());
        assertThat(out).isEqualTo(STATS_SUMMARY);
    }

    @Test
    void should_append_disclaimer_when_violation() {
        String out = gate.gate(STATS_SUMMARY, EvidenceAnchor.of(List.of(), "统计店数"),
                new HonorConfig(HonorAction.APPEND_DISCLAIMER));
        assertThat(out).contains("数据未能从本次查询核实");
    }

    @Test
    void should_pass_when_stats_tool_evidence_present() {
        EvidenceAnchor anchor = EvidenceAnchor.of(
                List.of(ToolEvidence.inline("queryTotalShops", "当前共有 120 家店")), "统计店数");
        String out = gate.gate(STATS_SUMMARY, anchor, new HonorConfig(HonorAction.APPEND_DISCLAIMER));
        assertThat(out).isEqualTo(STATS_SUMMARY);
    }

    @Test
    void should_off_always_pass_through() {
        String out = gate.gate(STATS_SUMMARY, EvidenceAnchor.of(List.of(), "统计店数"),
                new HonorConfig(HonorAction.OFF));
        assertThat(out).isEqualTo(STATS_SUMMARY);
    }

    @Test
    void should_pass_when_no_stats_assertion() {
        String out = gate.gate("这家店评分很高", EvidenceAnchor.of(List.of(), "随便聊聊"),
                new HonorConfig(HonorAction.APPEND_DISCLAIMER));
        assertThat(out).isEqualTo("这家店评分很高");
    }

    @Test
    void should_fail_open_on_null_input() {
        assertThat(gate.gate(null, EvidenceAnchor.of(List.of(), ""), HonorConfig.observe())).isNull();
        assertThat(gate.gate(STATS_SUMMARY, null, HonorConfig.observe())).isEqualTo(STATS_SUMMARY);
    }
}
