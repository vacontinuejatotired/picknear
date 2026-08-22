package com.hmdp.agent.prompt.repo;

import java.util.Optional;

/**
 * 远端提示词仓库策略接口（评审 13.3.2 / 方案 §4.5：与观测后端同套路——
 * Langfuse 只是提示词仓库的一个实现，未来可换 DB/Nacos）。
 * <p>
 * 顶层只面向本接口编程：{@code DefaultPromptService} / {@code PromptCacheWarmer} /
 * {@code PromptSeeder} / {@code PromptAdminController} 依赖类型统一为本接口。
 * </p>
 */
public interface RemotePromptRepository {

    /**
     * 拉取模板（含成功/404 负缓存、瞬时失败熔断——具体实现保证 Fail-Open）。
     *
     * @param key 模板键（Langfuse Prompt 名 / 内置资源文件名，一一对应）
     * @return 命中时返回模板文本；404/未配置/网络故障时返回 empty（不抛异常）
     */
    Optional<String> fetch(String key);

    /**
     * 推送模板到远端（创建/新版本，打 label）。
     *
     * @return true=推送成功；false=推送失败（不抛异常，调用方按 best-effort 处理）
     */
    boolean seed(String key, String content);

    /**
     * 清空全部缓存（管理端点 /hot-reload 用）。
     */
    void evictAll();
}