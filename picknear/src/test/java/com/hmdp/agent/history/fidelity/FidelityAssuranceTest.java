package com.hmdp.agent.history.fidelity;

import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.compression.SummaryResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FidelityAssuranceTest {

    private final FidelityAssurance assurance = new FidelityAssurance(new NumericKeyDataExtractor());

    private static AgentMessage msg(String user, String assistant) {
        AgentMessage m = new AgentMessage();
        m.setRole("user");
        m.setContent(user);
        m.setRole("assistant");
        m.setContent(assistant);
        return m;
    }

    @Test
    void should_backfill_dropped_numbers_in_summary() {
        List<AgentMessage> batch = List.of(msg("余额", "我的余额是1200元"));
        SummaryResult result = new SummaryResult("用户问了余额。", List.of(), false);

        String summary = assurance.apply(result, batch, 200, true);

        assertThat(summary).contains("1200");
    }

    @Test
    void should_not_backfill_when_numbers_already_in_summary() {
        List<AgentMessage> batch = List.of(msg("余额", "我的余额是1200元"));
        SummaryResult result = new SummaryResult("余额 1200 元。", List.of("1200"), false);

        String summary = assurance.apply(result, batch, 200, true);

        assertThat(summary).doesNotContain("关键数据保留");
    }

    @Test
    void should_skip_suffix_when_keep_keydata_disabled() {
        List<AgentMessage> batch = List.of(msg("余额", "我的余额是1200元"));
        SummaryResult result = new SummaryResult("用户问了余额。", List.of(), false);

        String summary = assurance.apply(result, batch, 200, false);

        assertThat(summary).doesNotContain("关键数据保留");
    }
}