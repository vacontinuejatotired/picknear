package com.hmdp.agent.prompt.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.prompt.config.PromptProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * LangfusePromptRepository — 远程仓库测试（MockRestServiceServer 桩网络）。
 * <p>
 * 覆盖：未配置不发请求、200 解析顶层 prompt、404 负缓存（不再打网络）、5xx 熔断。
 * </p>
 */
class LangfusePromptRepositoryTest {

    private PromptProperties props;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private LangfusePromptRepository repo;

    @BeforeEach
    void setUp() {
        props = new PromptProperties();
        props.setBaseUrl("https://langfuse.example.com");
        props.setBasicAuth("secret");
        builder = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(org.springframework.http.HttpHeaders.AUTHORIZATION, "Basic " + props.getBasicAuth());
        server = MockRestServiceServer.bindTo(builder).build();
        repo = new LangfusePromptRepository(props, new ObjectMapper());
        ReflectionTestUtils.setField(repo, "restClient", builder.build());
    }

    @Test
    void should_return_empty_when_not_configured() {
        PromptProperties emptyProps = new PromptProperties(); // baseUrl/basicAuth 为空
        LangfusePromptRepository emptyRepo = new LangfusePromptRepository(emptyProps, new ObjectMapper());

        assertThat(emptyRepo.fetch("agent.system.main")).as("未配置时不发请求").isEmpty();
    }

    @Test
    void should_fetch_prompt_content_on_200() {
        server.expect(queryParam("name", "agent.system.main"))
                .andExpect(queryParam("label", "production"))
                .andExpect(header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Basic secret"))
                .andRespond(withSuccess("{\"prompt\":\"你好 {{name}}\"}", MediaType.APPLICATION_JSON));

        Optional<String> result = repo.fetch("agent.system.main");

        assertThat(result).as("应解析顶层 prompt 字段").contains("你好 {{name}}");
    }

    @Test
    void should_negative_cache_on_404() {
        server.expect(queryParam("name", "agent.system.main"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"error\":\"LangfuseNotFoundError\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(repo.fetch("agent.system.main")).as("404 应返回 empty").isEmpty();
        // 负缓存命中：第二次 fetch 不应再打网络（有第二次请求会触发 Unexpected request AssertionError）
        assertThat(repo.fetch("agent.system.main")).as("404 负缓存内第二次应直接 empty").isEmpty();
    }

    @Test
    void should_short_circuit_on_5xx() {
        server.expect(queryParam("name", "agent.system.main"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(repo.fetch("agent.system.main")).as("5xx 应返回 empty（走熔断）").isEmpty();
        // 熔断期内第二次不再打网络
        assertThat(repo.fetch("agent.system.main")).as("熔断期内第二次应直接 empty").isEmpty();
    }

    @Test
    void should_use_cached_content_after_success() {
        server.expect(queryParam("name", "agent.system.main"))
                .andRespond(withSuccess("{\"prompt\":\"hi\"}", MediaType.APPLICATION_JSON));

        assertThat(repo.fetch("agent.system.main")).contains("hi");
        // 缓存命中：第二次不再打网络
        assertThat(repo.fetch("agent.system.main")).contains("hi");
    }
}
