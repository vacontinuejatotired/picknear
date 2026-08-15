package com.hmdp.agent.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.subagent.model.SubTaskResult;
import com.hmdp.agent.subagent.prompt.SubAgentPromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 子 Agent 回复解析器（从 SubTaskAgent 拆出，纯逻辑）。
 * <p>
 * 从 LLM 回复中提取 JSON 数据快照并降级兜底：
 * <ul>
 *   <li>LLM 未附加 JSON 快照 → rawResults={}，summary 取完整 content</li>
 *   <li>JSON 解析失败 → rawResults={}，摘要不变，日志记录警告</li>
 *   <li>data 字段超长（&gt;RAW_DATA_MAX_LENGTH）→ 截断</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class SubTaskResultParser {

    private final ObjectMapper objectMapper;

    public SubTaskResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析子 Agent 回复：提取 JSON 快照 → 归一 rawResults/errors → 裁剪摘要。
     *
     * @param content LLM 完整回复（末尾带 ===DATA_SNAPSHOT=== 标记）
     * @param startMs 子任务起始时间戳（executionTimeMs 计算用）
     */
    public SubTaskResult parse(String content, long startMs) {
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
                        // 对 data 字段做 RAW_DATA_MAX_LENGTH 字符截断，防 Token 爆炸
                        Object data = detail.get("data");
                        if (data instanceof String s && s.length() > SubAgentPromptTemplate.RAW_DATA_MAX_LENGTH) {
                            data = s.substring(0, SubAgentPromptTemplate.RAW_DATA_MAX_LENGTH) + "...(截断)";
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

        // 摘要 = 去除 JSON 快照部分后的纯文本；无快照时全文作为摘要
        String summary = snapshotStr != null
                ? content.substring(0, content.indexOf(SubAgentPromptTemplate.SNAPSHOT_BEGIN)).trim()
                : content;

        return SubTaskResult.builder()
                .summary(summary)
                .rawResults(rawResults.isEmpty() ? null : rawResults)
                .errors(errors.isEmpty() ? null : errors)
                .allSuccess(allSuccess)
                .executedTools(executedTools)
                .executionTimeMs(elapsed)
                .build();
    }

    /** 提取 ===DATA_SNAPSHOT=== ... ===DATA_SNAPSHOT_END=== 之间的 JSON */
    private String extractSnapshot(String content) {
        if (content == null) return null;
        int startIdx = content.indexOf(SubAgentPromptTemplate.SNAPSHOT_BEGIN);
        if (startIdx < 0) return null;
        startIdx += SubAgentPromptTemplate.SNAPSHOT_BEGIN.length();
        int endIdx = content.indexOf(SubAgentPromptTemplate.SNAPSHOT_END, startIdx);
        if (endIdx < 0) return null;
        return content.substring(startIdx, endIdx).trim();
    }
}
