package com.hmdp.agent.honesty.gate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NumericClaimExtractor — 统计断言句式抽取单测。
 */
class NumericClaimExtractorTest {

    private final NumericClaimExtractor extractor = new NumericClaimExtractor();

    @Test
    void should_extract_stats_claims_with_unit() {
        List<Claim> claims = extractor.extract("目前平台当前共有 123 家店");
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).kind()).isEqualTo(ClaimKind.STATS_COUNT);
        assertThat(claims.get(0).token()).isEqualTo("123家");
    }

    @Test
    void should_normalize_thousands_separator() {
        List<Claim> claims = extractor.extract("一共 1,200 篇博客，当前共有 3,456 个用户");
        assertThat(claims)
                .extracting(Claim::token)
                .containsExactly("1200篇", "3456个");
    }

    @Test
    void should_ignore_text_without_stats_pattern() {
        assertThat(extractor.extract("这家店评分很高，推荐看看")).isEmpty();
        assertThat(extractor.extract("")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }
}
