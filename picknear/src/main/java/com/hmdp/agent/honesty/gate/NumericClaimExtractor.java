package com.hmdp.agent.honesty.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数值断言抽取器（反编造 L3，Detector A 用）。
 * <p>
 * 抽取"平台统计断言"句式的数值标记：{@code 共/共计/一共/总共/当前共有 N 篇|家|位|条|个用户|家店|个店铺…}。
 * 这类断言若没有对应的统计工具证据支撑即高度疑似编造（T1）。去千分位归一化（1,200 → 1200）。
 * </p>
 */
@Slf4j
@Component
public class NumericClaimExtractor implements ClaimExtractor {

    private static final Pattern STATS = Pattern.compile(
            "(?:共|共计|一共|总共有|当前共有|总共)"
                    + "\\s*(?:有\\s*)?([0-9][0-9,]*(?:\\.[0-9]+)?)"
                    + "\\s*(篇|家|位|条|个|名)(?:用户|店铺|博客|店)?");

    @Override
    public List<Claim> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<Claim> result = new LinkedHashSet<>();
        Matcher matcher = STATS.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1).replace(",", "");
            String unit = matcher.group(2);
            result.add(new Claim(matcher.group(), ClaimKind.STATS_COUNT, token + unit));
        }
        return new ArrayList<>(result);
    }
}
