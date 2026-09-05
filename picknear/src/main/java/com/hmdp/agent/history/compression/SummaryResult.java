package com.hmdp.agent.history.compression;

import java.util.List;

/**
 * 摘要结果值对象：{@code summary} 摘要文本、{@code keyData} 必须保留的关键事实点、{@code truncated} 是否截断。
 */
public record SummaryResult(String summary, List<String> keyData, boolean truncated) {
}