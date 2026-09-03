package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.CompressionExecutorProperties;
import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.ConversationMemoryStore;
import com.hmdp.agent.history.fidelity.FidelityAssurance;
import com.hmdp.agent.model.Mem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 会话压缩编排（瘦编排）— 读 mem → 探测载荷 → 选批 → 摘要 → 提交游标 → 追平。
 * 无业务 if 堆叠（条件由选择器/摘要器封装）；摘要失败不推进游标并置 dirty（自愈）；
 * 追平循环封顶 {@code catchUpMax} 防活锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompressionOrchestrator {

    private final ConversationMemoryStore store;
    private final LoadProber loadProber;
    private final BatchSelector batchSelector;
    private final ConversationSummarizer summarizer;
    private final DirtyMarker dirtyMarker;
    private final CompressionExecutorProperties executorProperties;
    private final ContextCompressionProperties properties;
    private final FidelityAssurance fidelityAssurance;

    public void compressCatchUp(String conversationId, Long userId) {
        int rounds = 0;
        while (rounds < executorProperties.getCatchUpMax()) {
            Mem mem = store.read(conversationId, userId).orElse(Mem.empty());
            List<AgentMessage> pending = loadProber.loadPending(userId, conversationId, mem);
            Optional<ConversationBatch> batchOption = batchSelector.select(mem, pending, properties);
            if (batchOption.isEmpty()) {
                return;
            }
            ConversationBatch batch = batchOption.get();
            try {
                SummaryResult result = summarizer.summarize(mem.summary(), batch.messages());
                String finalSummary = fidelityAssurance.apply(result, batch.messages(),
                        properties.getSummary().getMaxTokens(), properties.getSummary().isKeepKeydata());
                Mem next = new Mem(finalSummary, batch.uptoId(),
                        mem.version() + 1, OffsetDateTime.now().toString());
                store.update(conversationId, userId, next);
                log.info("会话压缩完成 batch 至 uptoId={}，version={}，conversationId={}",
                        batch.uptoId(), next.version(), conversationId);
            } catch (Exception e) {
                log.warn("会话压缩失败，不推进游标，置 dirty 自愈 conversationId={}", conversationId, e);
                dirtyMarker.mark(conversationId, userId);
                return;
            }
            rounds++;
        }
    }
}