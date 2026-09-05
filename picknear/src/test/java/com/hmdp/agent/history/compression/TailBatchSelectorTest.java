package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.pressure.HeuristicTokenEstimator;
import com.hmdp.agent.model.Mem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TailBatchSelectorTest {

    private final TailBatchSelector selector = new TailBatchSelector(new HeuristicTokenEstimator());

    private static List<AgentMessage> msgs(int size) {
        List<AgentMessage> rows = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            AgentMessage m = new AgentMessage();
            m.setId((long) i);
            m.setRole(i % 2 == 1 ? "user" : "assistant");
            m.setContent("abcdefgh");
            rows.add(m);
        }
        return rows;
    }

    @Test
    void should_not_select_when_below_trigger() {
        ContextCompressionProperties props = new ContextCompressionProperties();

        Optional<ConversationBatch> batch = selector.select(Mem.empty(), msgs(18), 10, props);

        assertThat(batch).isEmpty();
    }

    @Test
    void should_select_oldest_batch_within_message_trigger() {
        ContextCompressionProperties props = new ContextCompressionProperties(); // 20 触发 / keep 10 轮 / batch 6 轮

        Optional<ConversationBatch> batch = selector.select(Mem.empty(), msgs(26), 10, props);

        assertThat(batch).isPresent();
        assertThat(batch.get().messages()).hasSize(6);          // min(2*6, 26-2*10=6)
        assertThat(batch.get().uptoId()).isEqualTo(6L);         // 最旧批次末条
    }

    @Test
    void should_keep_recent_tail_untouched() {
        ContextCompressionProperties props = new ContextCompressionProperties();

        Optional<ConversationBatch> batch = selector.select(Mem.empty(), msgs(26), 2, props);

        assertThat(batch).isPresent();
        assertThat(batch.get().messages()).hasSize(12);          // min(12, 26-4=22)
        assertThat(batch.get().uptoId()).isEqualTo(12L);
    }
}