package com.hmdp.agent.prompt.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.agent.prompt.config.PromptProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Langfuse Prompt Management 远程仓库（2026-08-06 实测 API）。
 * <p>
 * 端点：{@code GET {base}/api/public/prompts?name={name}&label={label}}（name 是查询参数）；
 * 200 响应内容在**顶层 {@code prompt} 字段**；404 为确定性结果 → 负缓存，网络失败/5xx → 短熔断。
 * </p>
 * <p>
 * 双 Caffeine 缓存：
 * <ul>
 *   <li>{@code contentCache}：成功文本 + 404 负结果（TTL=cacheTtl），防每请求刷 404</li>
 *   <li>{@code failureCache}：瞬时失败时间戳（TTL=failureCacheTtl），熔断后自动恢复探测</li>
 * </ul>
 * 构造**惰性**：RestClient 只是配置不建 URI，未配置（base-url/basic-auth 空）时不发任何远程请求。
 * </p>
 */
@Slf4j
@Component
public class LangfusePromptRepository {

    private final PromptProperties props;
    private final RestClient restClient;
    private final ObjectMapper json = new ObjectMapper();
    private final Cache<CacheKey, Optional<String>> contentCache;
    private final Cache<CacheKey, Long> failureCache;

    public LangfusePromptRepository(PromptProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getTimeout().toMillis());
        factory.setReadTimeout((int) props.getTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + props.getBasicAuth())
                .requestFactory(factory)
                .build();
        this.contentCache = Caffeine.newBuilder().expireAfterWrite(props.getCacheTtl()).build();
        this.failureCache = Caffeine.newBuilder().expireAfterWrite(props.getFailureCacheTtl()).build();
    }

    /** 拉取模板；成功/404 → 写 contentCache，网络失败/5xx → 写 failureCache，绝不抛给调用方 */
    public Optional<String> fetch(String promptName) {
        if (!props.isConfigured()) {
            return Optional.empty();
        }
        CacheKey key = new CacheKey(promptName, props.getDefaultLabel());
        Optional<String> hit = contentCache.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        if (failureCache.getIfPresent(key) != null) {
            return Optional.empty();
        }
        try {
            Optional<String> result = doFetch(promptName);
            if (result.isPresent()) {
                log.info("[prompt] Langfuse 命中 key={} label={}", promptName, props.getDefaultLabel());
            } else {
                log.warn("[prompt] Langfuse 无此 prompt（4xx），负缓存 key={}", promptName);
            }
            contentCache.put(key, result);
            return result;
        } catch (Exception e) {
            log.warn("[prompt] Langfuse 拉取失败(瞬时)，{}s 熔断 key={}, err={}",
                    props.getFailureCacheTtl().toSeconds(), promptName, e.getMessage());
            failureCache.put(key, System.currentTimeMillis());
            return Optional.empty();
        }
    }

    /** 清空全部缓存（seed/热改后调用） */
    public void clearCache() {
        contentCache.invalidateAll();
        failureCache.invalidateAll();
        log.info("[prompt] Langfuse 缓存已清空");
    }

    /** 推送模板到 Langfuse（创建/新版本，打 production label） */
    public boolean seed(String promptName, String content) {
        if (!props.isConfigured() || content == null) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", promptName);
        body.put("prompt", content);
        body.put("type", "text");
        body.put("isActive", true);
        body.put("labels", List.of(props.getDefaultLabel()));
        try {
            restClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/api/public/prompts").build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        res.close();
                        throw new PromptFetchException("Langfuse seed 失败 HTTP " + res.getRawStatusCode());
                    })
                    .toBodilessEntity();
            log.info("[prompt] 已推送 Langfuse: {} ({})", promptName, props.getDefaultLabel());
            return true;
        } catch (Exception e) {
            log.warn("[prompt] 推送 Langfuse 失败 key={}, err={}", promptName, e.getMessage());
            return false;
        }
    }

    /** 实际请求；2xx → 解析内容；4xx（404 不存在/401 认证失败）→ empty 负缓存；5xx → 抛（走熔断） */
    private Optional<String> doFetch(String promptName) {
        ResponseEntity<String> resp = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/public/prompts")
                        .queryParam("name", promptName)
                        .queryParam("label", props.getDefaultLabel())
                        .build())
                .retrieve()
                // 4xx 是确定性结果（404 不存在/401 认证失败）：不 close（close 后 body 无法提取，
                // 会抛 "Error while extracting response" 被当成瞬时故障），用空 handler 让 body
                // 正常提取，靠下方状态码判断走负缓存；5xx 是瞬时故障 → 抛异常走 30s 熔断
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {})
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    res.close();
                    throw new PromptFetchException("Langfuse GET 5xx HTTP " + res.getRawStatusCode());
                })
                .toEntity(String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            return Optional.empty();
        }
        String content = extractPromptContent(resp.getBody());
        if (!StringUtils.hasText(content)) {
            log.warn("[prompt] 响应无有效 prompt 字段 key={}", promptName);
            return Optional.empty();
        }
        return Optional.of(content);
    }

    /** 解析模板内容：主形态顶层 prompt 字段；兜底 versions[] 按 label 匹配/取最新 */
    private String extractPromptContent(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode promptField = root.get("prompt");
            if (promptField != null && promptField.isTextual()
                    && StringUtils.hasText(promptField.asText())) {
                return promptField.asText();
            }
            JsonNode versions = root.get("versions");
            if (versions != null && versions.isArray() && !versions.isEmpty()) {
                JsonNode matched = null;
                for (JsonNode v : versions) {
                    JsonNode labels = v.get("labels");
                    if (labels != null && labels.isArray()) {
                        for (JsonNode l : labels) {
                            if (l.isTextual() && props.getDefaultLabel().equals(l.asText())) {
                                matched = v;
                                break;
                            }
                        }
                    }
                    if (matched != null) break;
                }
                if (matched == null) {
                    matched = versions.get(versions.size() - 1);
                }
                JsonNode content = matched.get("prompt");
                if (content != null && content.isTextual()
                        && StringUtils.hasText(content.asText())) {
                    return content.asText();
                }
            }
        } catch (Exception e) {
            log.warn("[prompt] 响应 JSON 解析失败: {}", e.getMessage());
        }
        return null;
    }

    public record CacheKey(String name, String label) {}

    private static class PromptFetchException extends RuntimeException {
        PromptFetchException(String message) {
            super(message);
        }
    }
}
