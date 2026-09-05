package com.hmdp.agent.history.fidelity;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数值/日期关键点抽取器 — 正则提取金额、数量、百分比、日期等，去重且规范化千分位（1,200 与 1200 视为同值）。
 */
@Component
public class NumericKeyDataExtractor implements KeyDataExtractor {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NUMBER = Pattern.compile(
            "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?(?:%|元|万|亿|年|月|日|折|个|条|篇|家|人|次|km|m|°C|℃)?");

    @Override
    public List<String> extract(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        collect(result, DATE, text);
        collect(result, NUMBER, text);
        return new ArrayList<>(result);
    }

    private void collect(Set<String> result, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            result.add(normalize(matcher.group()));
        }
    }

    /** 去千分位，统一数值事实点（1,200 → 1200） */
    private String normalize(String value) {
        return value.replace(",", "");
    }
}