package com.hmdp.agent.history;

import com.hmdp.agent.entity.AgentMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReplayBudgetTrim — 回放字符预算裁剪单测（超预算丢最旧、最新恒保留、升序保持）。
 */
@ExtendWith(MockitoExtension.class)
class ReplayBudgetTrimTest {

    private final ReplayBudgetTrim trim = new ReplayBudgetTrim();

    @Test
    void should_keep_all_when_within_budget() {
        List<AgentMessage> result = trim.trimToBudget(List.of(msg("第1条"), msg("第2条")), 100);

        assertThat(result).as("预算充足应原样保留").hasSize(2);
    }

    @Test
    void should_drop_oldest_when_over_budget() {
        // 升序（老→新）：AAAA(4) BBBB(4) CCCC(4) DD(2) EE(2)，预算 8
        List<AgentMessage> result = trim.trimToBudget(
                List.of(msg("AAAA"), msg("BBBB"), msg("CCCC"), msg("DD"), msg("EE")), 8);

        assertThat(result)
                .as("超预算应从最旧丢，保留升序的最近部分")
                .extracting(AgentMessage::getContent)
                .containsExactly("CCCC", "DD", "EE");
    }

    @Test
    void should_keep_newest_even_when_single_over_budget() {
        List<AgentMessage> result = trim.trimToBudget(
                List.of(msg("old"), msg("this is a very long newest message exceeding budget")), 10);

        assertThat(result).as("最新一条恒保留").hasSize(1);
        assertThat(result.get(0).getContent()).as("保留的是最新一条").isEqualTo("this is a very long newest message exceeding budget");
    }

    @Test
    void should_not_touch_when_budget_non_positive() {
        List<AgentMessage> rows = List.of(msg("a"), msg("b"));

        assertThat(trim.trimToBudget(rows, 0)).as("0 预算=不限制").hasSize(2);
        assertThat(trim.trimToBudget(rows, -5)).as("负预算=不限制").hasSize(2);
    }

    @Test
    void should_return_empty_when_empty() {
        assertThat(trim.trimToBudget(List.of(), 10)).as("空列表原样返回").isEmpty();
    }

    private static AgentMessage msg(String content) {
        return new AgentMessage().setContent(content);
    }
}