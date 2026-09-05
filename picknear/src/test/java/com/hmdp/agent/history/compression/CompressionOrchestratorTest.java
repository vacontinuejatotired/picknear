package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.CompressionExecutorProperties;
import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.config.ReplayProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.ConversationMemoryStore;
import com.hmdp.agent.history.fidelity.FidelityAssurance;
import com.hmdp.agent.model.Mem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompressionOrchestratorTest {

    @Mock private ConversationMemoryStore store;
    @Mock private LoadProber loadProber;
    @Mock private BatchSelector batchSelector;
    @Mock private ConversationSummarizer summarizer;
    @Mock private DirtyMarker dirtyMarker;
    @Mock private CompressionExecutorProperties executorProperties;
    @Mock private ContextCompressionProperties properties;
    @Mock private FidelityAssurance fidelityAssurance;
    @Mock private ReplayProperties replayProperties;

    @InjectMocks private CompressionOrchestrator orchestrator;

    private static AgentMessage msg(long id) {
        AgentMessage m = new AgentMessage();
        m.setId(id);
        m.setRole("user");
        m.setContent("你好");
        return m;
    }

    @Test
    void should_commit_advanced_mem_on_success() {
        ContextCompressionProperties realProps = new ContextCompressionProperties();
        when(properties.getSummary()).thenReturn(realProps.getSummary());
        when(executorProperties.getCatchUpMax()).thenReturn(1);
        when(store.read(eq("cid"), eq(1L))).thenReturn(Optional.empty());
        when(loadProber.loadPending(eq(1L), eq("cid"), any())).thenReturn(List.of(msg(1L), msg(2L)));
        when(batchSelector.select(any(), anyList(), anyInt(), any()))
                .thenReturn(Optional.of(new ConversationBatch(2L, List.of(msg(1L), msg(2L)))));
        when(summarizer.summarize(anyString(), anyList()))
                .thenReturn(new SummaryResult("sum", List.of(), false));
        when(fidelityAssurance.apply(any(), anyList(), anyInt(), anyBoolean())).thenReturn("sum");

        orchestrator.compressCatchUp("cid", 1L);

        ArgumentCaptor<Mem> mem = ArgumentCaptor.forClass(Mem.class);
        verify(store).update(eq("cid"), eq(1L), mem.capture());
        assertThat(mem.getValue().summary()).isEqualTo("sum");
        assertThat(mem.getValue().uptoId()).isEqualTo(2L);
        assertThat(mem.getValue().version()).isEqualTo(1);
        verify(dirtyMarker).clear("cid");
    }

    @Test
    void should_not_advance_on_summarizer_failure_and_clear_dirty() {
        when(executorProperties.getCatchUpMax()).thenReturn(1);
        when(store.read(eq("cid"), eq(1L))).thenReturn(Optional.empty());
        when(loadProber.loadPending(eq(1L), eq("cid"), any())).thenReturn(List.of(msg(1L)));
        when(batchSelector.select(any(), anyList(), anyInt(), any()))
                .thenReturn(Optional.of(new ConversationBatch(1L, List.of(msg(1L)))));
        when(summarizer.summarize(anyString(), anyList()))
                .thenThrow(new IllegalStateException("模型失败"));

        orchestrator.compressCatchUp("cid", 1L);

        verify(store, never()).update(any(), any(), any());
        verify(dirtyMarker).clear("cid");   // 失败不再 mark（新写回合自然重试），收尾仍清 dirty 防 sweeper 风暴
    }
}