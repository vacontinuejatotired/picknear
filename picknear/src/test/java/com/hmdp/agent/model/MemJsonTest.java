package com.hmdp.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mem record 的 Jackson 序列化往返验证。
 * RedisConversationMemoryStore 用项目 ObjectMapper 存 Mem JSON——若 record 反序列化在
 * 运行期 ObjectMapper 上不生效，update 会静默失败、压缩失效。此测试用裸 Jackson 探底，
 * 发现失败即需在 store 注入的 ObjectMapper 上补 record 支持（ParameterNamesModule 等）。
 */
class MemJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_roundtrip_mem_record_via_json() throws Exception {
        Mem original = new Mem("用户余额1200元，收藏店铺3家", 41L, 2, "2026-09-04T10:00:00");

        String json = mapper.writeValueAsString(original);
        Mem back = mapper.readValue(json, Mem.class);

        assertThat(back.summary()).isEqualTo(original.summary());
        assertThat(back.uptoId()).isEqualTo(41L);
        assertThat(back.version()).isEqualTo(2);
        assertThat(back.updatedAt()).isEqualTo(original.updatedAt());
    }
}