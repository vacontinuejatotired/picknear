package com.hmdp.agent.prompt.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.agent.prompt.entity.LocalPrompt;
import com.hmdp.agent.prompt.mapper.LocalPromptMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 本地提示词仓库 — Langfuse 不可用时的数据库兜底
 * <p>
 * 优先级：Langfuse → 本地数据库 → 内置模板
 * 本地存储作为最后一道防线，确保即使云端完全不可用也能正常响应。
 * </p>
 */
@Slf4j
@Component
public class LocalPromptRepository {

    private final LocalPromptMapper localPromptMapper;

    public LocalPromptRepository(LocalPromptMapper localPromptMapper) {
        this.localPromptMapper = localPromptMapper;
    }

    /**
     * 从本地数据库获取提示词
     *
     * @param key   提示词键名
     * @param label 标签（如 production）
     * @return 提示词内容
     */
    public Optional<String> fetch(String key, String label) {
        try {
            LambdaQueryWrapper<LocalPrompt> wrapper = new LambdaQueryWrapper<LocalPrompt>()
                    .eq(LocalPrompt::getPromptKey, key)
                    .eq(LocalPrompt::getLabel, label);
            LocalPrompt record = localPromptMapper.selectOne(wrapper);
            if (record != null && record.getContent() != null && !record.getContent().isEmpty()) {
                log.info("[prompt] 本地数据库命中 key={} label={}", key, label);
                return Optional.of(record.getContent());
            }
        } catch (Exception e) {
            log.warn("[prompt] 本地数据库查询失败 key={}, err={}", key, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 保存或更新提示词到本地数据库
     */
    public void save(String key, String content, String label, String source) {
        try {
            LambdaQueryWrapper<LocalPrompt> wrapper = new LambdaQueryWrapper<LocalPrompt>()
                    .eq(LocalPrompt::getPromptKey, key)
                    .eq(LocalPrompt::getLabel, label);
            LocalPrompt existing = localPromptMapper.selectOne(wrapper);

            if (existing != null) {
                existing.setContent(content);
                existing.setSource(source);
                existing.setUpdatedAt(LocalDateTime.now());
                localPromptMapper.updateById(existing);
            } else {
                LocalPrompt record = new LocalPrompt();
                record.setPromptKey(key);
                record.setContent(content);
                record.setLabel(label);
                record.setSource(source);
                record.setCreatedAt(LocalDateTime.now());
                record.setUpdatedAt(LocalDateTime.now());
                localPromptMapper.insert(record);
            }
        } catch (Exception e) {
            log.warn("[prompt] 本地数据库保存失败 key={}, err={}", key, e.getMessage());
        }
    }
}
