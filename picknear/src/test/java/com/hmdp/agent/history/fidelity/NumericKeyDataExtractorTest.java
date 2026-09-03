package com.hmdp.agent.history.fidelity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NumericKeyDataExtractorTest {

    private final NumericKeyDataExtractor extractor = new NumericKeyDataExtractor();

    @Test
    void should_extract_numbers_dates_and_percents() {
        List<String> keys = extractor.extract("订单号 1200，阅读量 5000，核销率 92%，日期 2024-05-01，会员 T-100 折");

        assertThat(keys).contains("1200", "5000", "92%", "2024-05-01", "100");
    }

    @Test
    void should_normalize_thousand_separator() {
        List<String> keys = extractor.extract("阅读量 1,200，另一处 1200");

        assertThat(keys).contains("1200").doesNotContain("1,200");
    }

    @Test
    void should_return_empty_for_empty_text() {
        assertThat(extractor.extract("")).isEmpty();
    }
}