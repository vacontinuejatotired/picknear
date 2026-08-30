package com.hmdp.agent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.execution.model.ExecutionOutput;
import com.hmdp.agent.prompt.builder.TemplateConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 子 Agent 回复解析器（纯逻辑）。
 * <p>
 * 从 LLM 回复中提取 JSON 数据快照并降级兜底。
 * </p>
 */
@Slf4j
@Component
public class ResultParser {

    private final ObjectMapper objectMapper;

    public ResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ExecutionOutput parse(String content, long startMs) {
        long elapsed = System.currentTimeMillis() - startMs;

        String snapshotStr = extractSnapshot(content);
        Map<String, Object> rawResults = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        boolean allSuccess = true;
        List<String> executedTools = new ArrayList<>();

        if (snapshotStr != null) {
            try {
                Map<String, Object> snapshot = objectMapper.readValue(snapshotStr,
                        new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                    executedTools.add(entry.getKey());
                    if (entry.getValue() instanceof Map<?, ?> detail) {
                        String status = Objects.toString(detail.get("status"), "");
                        if ("error".equals(status)) {
                            allSuccess = false;
                            errors.put(entry.getKey(),
                                    Objects.toString(detail.get("message"), "未知错误"));
                        }
                        Object data = detail.get("data");
                        if (data instanceof String s && s.length() > TemplateConstants.RAW_DATA_MAX_LENGTH) {
                            data = s.substring(0, TemplateConstants.RAW_DATA_MAX_LENGTH) + "...(截断)";
                        }
                        rawResults.put(entry.getKey(), data != null ? data : detail);
                    } else {
                        rawResults.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception e) {
                log.warn("[SubAgent] JSON 快照解析失败, 将使用完整回复作为摘要 [err={}]", e.getMessage());
            }
        } else {
            log.warn("[SubAgent] 未检测到 JSON 快照标记, rawResults 将为空");
        }

        String summary = snapshotStr != null
                ? content.substring(0, content.indexOf(TemplateConstants.SNAPSHOT_BEGIN)).trim()
                : content;

        return ExecutionOutput.builder()
                .summary(summary)
                .rawResults(rawResults.isEmpty() ? null : rawResults)
                .errors(errors.isEmpty() ? null : errors)
                .allSuccess(allSuccess)
                .executedTools(executedTools)
                .executionTimeMs(elapsed)
                .build();
    }

    private String extractSnapshot(String content) {
        if (content == null) return null;
        int startIdx = content.indexOf(TemplateConstants.SNAPSHOT_BEGIN);
        if (startIdx < 0) return null;
        startIdx += TemplateConstants.SNAPSHOT_BEGIN.length();
        int endIdx = content.indexOf(TemplateConstants.SNAPSHOT_END, startIdx);
        if (endIdx < 0) return null;
        return content.substring(startIdx, endIdx).trim();
    }
}
