package com.hmdp.agent.execution;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.plan.model.SubTaskStatus;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具执行状态记录器 {@link ToolExecutionRecorder} 纯单元测试（无 Spring 上下文）。
 * <p>
 * 守护点：成功/失败终态回填、重复调用不占新号（状态覆盖）、span 缺失 Fail-Open、
 * reset 解绑、并发 record 序号唯一。
 * </p>
 */
class ToolExecutionRecorderTest {

    /** 内存 fake span：记录参数化属性写入（三参 set 的 segment 展开成 key） */
    private static final class FakeSpan implements AgentSpan {
        private final Map<String, String> attrs = new ConcurrentHashMap<>();

        @Override
        public AgentSpan set(AgentField field, String value) {
            attrs.put(field.key(), value);
            return this;
        }

        @Override
        public AgentSpan set(AgentField field, String segment, String value) {
            attrs.put(field.key(segment), value);
            return this;
        }

        @Override
        public AgentSpan attribute(String key, String value) {
            attrs.put(key, value);
            return this;
        }

        @Override
        public Observation.Scope openScope() {
            return null;
        }

        @Override
        public void closeRootScope() {
        }

        @Override
        public void end() {
        }

        @Override
        public String spanName() {
            return "agent.subagent";
        }

        Map<String, String> attrs() {
            return attrs;
        }
    }

    private final ToolExecutionRecorder recorder = new ToolExecutionRecorder();

    @Test
    void flush_shouldWriteToolEntriesForSuccessAndFailure() {
        FakeSpan span = new FakeSpan();
        recorder.reset(span);
        recorder.record("queryWeather", SubTaskStatus.COMPLETED);
        recorder.record("queryBlog", SubTaskStatus.FAILED);
        recorder.flush();

        assertThat(span.attrs())
                .containsEntry("tool.0.name", "queryWeather")
                .containsEntry("tool.0.status", "COMPLETED")
                .containsEntry("tool.1.name", "queryBlog")
                .containsEntry("tool.1.status", "FAILED");
    }

    @Test
    void repeatedTool_shouldNotTakeNewIndex_andStatusOverrides() {
        FakeSpan span = new FakeSpan();
        recorder.reset(span);
        recorder.record("queryWeather", SubTaskStatus.COMPLETED);
        recorder.record("queryBlog", SubTaskStatus.COMPLETED);
        recorder.record("queryWeather", SubTaskStatus.FAILED); // 重复调用：不占新号，状态覆盖
        recorder.flush();

        assertThat(span.attrs())
                .containsEntry("tool.0.name", "queryWeather")
                .containsEntry("tool.0.status", "FAILED")
                .containsEntry("tool.1.name", "queryBlog")
                .doesNotContainKey("tool.2.name");
    }

    @Test
    void flush_withoutSpan_shouldBeSilent() {
        recorder.reset(null);
        recorder.record("queryWeather", SubTaskStatus.COMPLETED);
        recorder.flush(); // Fail-Open：不抛
    }

    @Test
    void resetNull_shouldUnbindSpan() {
        FakeSpan span = new FakeSpan();
        recorder.reset(span);
        recorder.record("queryWeather", SubTaskStatus.COMPLETED);
        recorder.reset(null);
        recorder.flush();

        assertThat(span.attrs()).isEmpty();
    }

    @Test
    void concurrentRecords_shouldAllGetUniqueIndexes() throws Exception {
        FakeSpan span = new FakeSpan();
        recorder.reset(span);
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    recorder.record("tool" + idx, SubTaskStatus.COMPLETED);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        recorder.flush();
        assertThat(span.attrs()).hasSize(2 * n);
        // 并发下先占号者不定：断言序号唯一、工具全覆盖、无丢失
        Set<String> names = new HashSet<>();
        for (int i = 0; i < n; i++) {
            names.add(span.attrs().get("tool." + i + ".name"));
            assertThat(span.attrs()).containsEntry("tool." + i + ".status", "COMPLETED");
        }
        assertThat(names).hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat(names).contains("tool" + i);
        }
    }
}
