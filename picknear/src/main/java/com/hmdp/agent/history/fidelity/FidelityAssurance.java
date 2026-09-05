package com.hmdp.agent.history.fidelity;

import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.compression.SummaryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 摘要保真断言 — 从批次原文抽关键数据点，断言出现在新摘要/摘要器声明的 keyData 中；
 * 缺漏（dropped 非空）时按容量回填【关键数据保留：…】，绝不静默丢数；容量不足仅记日志（原值永在 agent_message 可查）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FidelityAssurance {

    private final KeyDataExtractor keyDataExtractor;

    /**
     * @return 经保真回填后的最终摘要文本（可能带数据点后缀）
     * @param maxLength  摘要容量（超出取不到容量则仅日志）
     * @param keepKeyData 是否启用关键数据保留后缀
     */
    public String apply(SummaryResult result, List<AgentMessage> batch, int maxLength, boolean keepKeyData) {
        String summary = result.summary();
        if (batch.isEmpty()) {
            return summary;
        }
        List<String> batchKeyData = keyDataExtractor.extract(concat(batch));
        List<String> dropped = new ArrayList<>();
        for (String key : batchKeyData) {
            if (!contains(summary, key) && !result.keyData().contains(key)) {
                dropped.add(key);
            }
        }
        if (dropped.isEmpty()) {
            return summary;
        }

        String suffix = "【关键数据保留：" + String.join("、", dropped) + "】";
        if (keepKeyData && summary.length() + suffix.length() <= maxLength) {
            return summary + suffix;
        }
        if (keepKeyData && maxLength > summary.length()) {
            int remain = maxLength - summary.length();
            String trimmed = "【关键数据保留：" + String.join("、", dropped) + "】";
            return summary + (trimmed.length() > remain ? trimmed.substring(0, remain) : trimmed);
        }
        log.warn("摘要保真：{} 个关键数据点未并入摘要（容量不足），原值仍可在 agent_message 查询：{}",
                dropped.size(), dropped);
        return summary;
    }

    private static String concat(List<AgentMessage> batch) {
        StringBuilder sb = new StringBuilder();
        for (AgentMessage m : batch) {
            sb.append(m.getContent() == null ? "" : m.getContent()).append(' ');
        }
        return sb.toString();
    }

    private static boolean contains(String text, String token) {
        if (text == null || token == null || text.isEmpty()) {
            return false;
        }
        if (text.contains(token)) {
            return true;
        }
        // 数字词边界匹配：'1200元' 与文本中的 '1200' 视为同值；'12000' 不误命中 '1200'
        String digits = token.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<!\\d)" + Pattern.quote(digits) + "(?!\\d)")
                .matcher(text)
                .find();
    }
}