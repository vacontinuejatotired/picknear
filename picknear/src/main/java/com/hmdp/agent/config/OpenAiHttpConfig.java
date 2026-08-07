package com.hmdp.agent.config;

import io.netty.channel.ChannelOption;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MaaS（百炼）OpenAI 兼容端点 HTTP 客户端连接池与超时配置。
 * <p>
 * 提供自定义 {@link OpenAiApi} Bean，注入带连接池的同步和流式 HTTP 客户端。
 * {@code OpenAiChatAutoConfiguration} 的 {@code openAiApi(...)} 方法带
 * {@code @ConditionalOnMissingBean}，本 Bean 定义后自动配置回退，其
 * {@code openAiChatModel(...)} 会按类型装配本版本（连接池）构建模型。
 * <ul>
 *   <li><b>同步模式</b>（{@code chatReturnStringResult}）：使用 Apache HttpClient 5 + 连接池，
 *       替代默认的 {@code HttpURLConnection}（无连接池，每次新建 TCP 连接）；</li>
 *   <li><b>流式模式</b>（SSE）：使用 Reactor Netty 连接池，调优空闲回收与获取超时参数。</li>
 * </ul>
 *
 * <h3>为什么是 OpenAI 客户端（2026-08-07）</h3>
 * MaaS 工作空间端点是 OpenAI 兼容协议：base-url 到 {@code /compatible-mode} 为止，
 * SDK 的 {@code completionsPath}（默认 {@code /v1/chat/completions}）自动拼接成
 * {@code .../compatible-mode/v1/chat/completions}。请求体用 OpenAI 格式（{@code {"model","messages"}}）。
 * DashScope 经典路径 {@code /api/v1/services/aigc/text-generation/generation} 会被 MaaS 网关静默丢连接
 * （0 字节、连接复位），表现即 {@code NoHttpResponseException: ... failed to respond}；
 * 而 DashScope 格式请求体（{@code input.messages}）打到 compatible-mode 端点会 400。
 *
 * <h3>保守参数</h3>
 * <pre>
 *   maxConnections          200（同步 + 流式各自独立池）
 *   maxIdleTime             30s
 *   pendingAcquireTimeout   10s
 *   connectTimeout          10s（统一）
 *   responseTimeout         60s（流式指等首字节，流间空闲仍由 SseEmitter 30min 兜底）
 * </pre>
 *
 * <h3>Bean 冲突规避</h3>
 * <p>Bean 名称使用 {@code customOpenAiApi} 而非自动配置的同名方法，配合
 * {@code @Primary} 确保自动装配时优先使用本配置版本。</p>
 *
 * @see com.hmdp.agent.service.impl.AiServiceImpl
 */
@Configuration
public class OpenAiHttpConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiHttpConfig.class);

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    /** MaaS compatible-mode base-url（到 /compatible-mode 为止；SDK 会自动拼 /v1/chat/completions） */
    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    /**
     * 提供自定义 {@link OpenAiApi} Bean，覆盖自动配置。
     * <p>
     * Bean 名称 {@code customOpenAiApi} 避免与自动配置中的 {@code openAiApi} 方法重名；
     * {@code @Primary} 确保 {@code OpenAiChatModel} 注入此版本。
     */
    @Bean("customOpenAiApi")
    @Primary
    public OpenAiApi openAiApi() {
        RestClient.Builder restClientBuilder = createPooledRestClient();
        WebClient.Builder webClientBuilder = createPooledWebClient();

        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();
    }

    /**
     * 创建带连接池的 RestClient（用于同步非流式 AI 请求）。
     * <p>
     * OpenAiApi 默认使用 {@code SimpleClientHttpRequestFactory}（基于
     * {@code HttpURLConnection}），每次新建 TCP 连接。此处替换为
     * Apache HttpClient 5 连接池：
     * <ul>
     *   <li>连接超时 10s（原 60s，减少线程阻塞时间）；</li>
     *   <li>读取超时 60s（原 3min，仍对大模型响应友好）；</li>
     *   <li>{@code validateAfterInactivity=5s}，检测服务端已断开的连接。</li>
     * </ul>
     */
    private RestClient.Builder createPooledRestClient() {
        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(200);
        connManager.setDefaultMaxPerRoute(200);
        connManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(10))       // TCP 建连超时
                .setSocketTimeout(Timeout.ofSeconds(60))        // 读取响应超时
                .setTimeToLive(30, TimeUnit.SECONDS)            // 连接最大存活
                .build());
        connManager.setValidateAfterInactivity(TimeValue.ofMilliseconds(5000)); // 5s 后台验证连接活性

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .evictIdleConnections(TimeValue.ofSeconds(30))  // 30s 空闲回收
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);

        return RestClient.builder()
                .requestFactory(factory);
    }

    /**
     * 创建带连接池的 WebClient（用于流式 SSE AI 请求）。
     * <p>
     * OpenAiApi 默认 Netty 连接池参数（500/45s/60s-acquire/30s-evict），
     * 此处调优为保守参数。
     * <p>
     * <b>重要：responseTimeout 必须与 SseEmitter 超时（30min）对齐。</b>
     * Reactor Netty 的 {@code responseTimeout} 不是"首字节超时"，
     * 而是<b>整个响应体的最大等待时间</b>，包括 SSE 流中 chunk 之间的空闲时间。
     * 若设 60s，DeepSeek 模型推理停顿超过 60s 时 Netty 会关闭连接，
     * 服务端继续发送数据 → {@code Connection reset}。
     */
    private WebClient.Builder createPooledWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("maas-http-pool")
                .maxConnections(200)
                .maxIdleTime(Duration.ofSeconds(30))            // 空闲 30s 后关闭
                .maxLifeTime(Duration.ofMinutes(10))            // 连接最大存活 10 min
                .pendingAcquireTimeout(Duration.ofSeconds(10))  // 从池中获取连接超时
                .evictInBackground(Duration.ofSeconds(30))      // 每 30s 后台回收
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)    // 建连超时 10s
                .option(ChannelOption.SO_KEEPALIVE, true)               // TCP keepalive 防止 SLB 空闲断开
                .responseTimeout(Duration.ofMinutes(30));               // 与 SseEmitter(30min) 对齐，不提前断流

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
