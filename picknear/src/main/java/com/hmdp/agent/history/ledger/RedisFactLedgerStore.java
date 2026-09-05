package com.hmdp.agent.history.ledger;

import com.hmdp.agent.execution.model.ToolEvidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 事实账本 Redis 适配器（反编造 L4）——List 结构逐行追加 + 滑动 TTL。
 * <p>
 * key：{@code agent:conv:{cid}:ledger}（与会话记忆 key 前缀同族）；
 * 用 Redis List 而非单 key JSON，天然避免"读-改-写"竞态；命中即滑动续期（封顶由预算裁旧行保证体积）。
 * 读取按预算保留最新行、裁最旧。fail-open：Redis 异常只记日志返回空，不阻断。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisFactLedgerStore implements FactLedgerStore {

    private static final String KEY_PREFIX = "agent:conv:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${agent.honesty.ledger.ttl-seconds:604800}")
    private long ttlSeconds;

    @Value("${agent.honesty.ledger.max-chars:2000}")
    private int maxChars;

    @Override
    public void append(String conversationId, List<ToolEvidence> evidence) {
        if (conversationId == null || evidence == null || evidence.isEmpty()) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>(evidence.size());
            for (ToolEvidence e : evidence) {
                String line = LedgerLineComposer.compose(e);
                if (line != null && !line.isBlank()) {
                    lines.add(line);
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            String key = key(conversationId);
            stringRedisTemplate.opsForList().rightPushAll(key, lines);
            stringRedisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("事实账本写入失败（fail-open），conversationId={}", conversationId, e);
        }
    }

    @Override
    public String read(String conversationId) {
        if (conversationId == null) {
            return "";
        }
        try {
            List<String> lines = stringRedisTemplate.opsForList().range(key(conversationId), 0, -1);
            if (lines == null || lines.isEmpty()) {
                return "";
            }
            // 保留最新行：从尾部累积到预算，超出裁最旧
            List<String> kept = new ArrayList<>();
            int used = 0;
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                int add = line.length() + (kept.isEmpty() ? 0 : 1);
                if (used + add > maxChars && !kept.isEmpty()) {
                    break;
                }
                if (used + add > maxChars && kept.isEmpty()) {
                    kept.add(truncateToBudget(line));
                    used = line.length();
                    break;
                }
                kept.add(0, line);
                used += add;
            }
            return String.join("\n", kept);
        } catch (Exception e) {
            log.warn("事实账本读取失败（fail-open），conversationId={}", conversationId, e);
            return "";
        }
    }

    private String truncateToBudget(String line) {
        return line.length() <= maxChars ? line : line.substring(0, maxChars);
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId + ":ledger";
    }
}
