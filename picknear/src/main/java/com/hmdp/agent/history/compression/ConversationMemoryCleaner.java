package com.hmdp.agent.history.compression;

import com.hmdp.agent.history.ConversationMemoryKeyFactory;
import com.hmdp.agent.history.ConversationMemoryStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 压缩待补清扫器（低频兜底）— 事件投递被拒 / worker 失败置 dirty 后自愈；且清过期/TTL。
 */
@Slf4j
@Component
public class ConversationMemoryCleaner {

    private static final String DIRTY_PATTERN = "agent:conv:*:dirty";
    private static final String DIRTY_PREFIX = "agent:conv:";
    private static final String DIRTY_SUFFIX = ":dirty";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ConversationMemoryKeyFactory keyFactory;

    @Resource
    private DirtyMarker dirtyMarker;

    @Resource
    private CompressionOrchestrator orchestrator;

    @Resource
    private ConversationMemoryStore store;

    @Scheduled(fixedDelayString = "${agent.compression-executor.sweeper-interval:300000}")
    public void sweep() {
        try {
            Set<String> dirtyKeys = stringRedisTemplate.keys(DIRTY_PATTERN);
            if (dirtyKeys == null) {
                return;
            }
            for (String key : dirtyKeys) {
                String conversationId = extractConversationId(key);
                if (conversationId.isEmpty()) {
                    continue;
                }
                Long userId = dirtyMarker.readAndClear(conversationId);
                if (userId != null) {
                    log.info("sweeper 触发压缩补跑 conversationId={}", conversationId);
                    orchestrator.compressCatchUp(conversationId, userId);
                }
            }
        } catch (Exception e) {
            log.debug("sweep 扫描失败（兜底，下周期重试）", e);
        }
    }

    /** 会话归档/删除入口（预留接未来归档端点）：清理记忆视图与 dirty 标记。 */
    public void purge(String conversationId) {
        store.delete(conversationId);
        stringRedisTemplate.delete(keyFactory.dirtyKey(conversationId));
    }

    private static String extractConversationId(String dirtyKey) {
        return dirtyKey.startsWith(DIRTY_PREFIX) && dirtyKey.endsWith(DIRTY_SUFFIX)
                ? dirtyKey.substring(DIRTY_PREFIX.length(), dirtyKey.length() - DIRTY_SUFFIX.length())
                : "";
    }
}