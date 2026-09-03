package com.hmdp.agent.history.fidelity;

import java.util.List;

/**
 * 关键数据点抽取端口（策略接口）— 从文本抽"压缩后必须保留"的事实点（数值/日期/专名等）。
 */
public interface KeyDataExtractor {

    List<String> extract(String text);
}