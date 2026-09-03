package com.hmdp.agent.history.pressure;

import org.springframework.stereotype.Component;

/**
 * 启发式 token 估算：CJK≈1token/字、其余≈0.25/字符，逐码点累计向上取整。
 * 确定性、单调、零网络——只用于压缩触发与预算，不要求精确。
 * 每条消息头开销由调用方按消息条数叠加。
 */
@Component
public class HeuristicTokenEstimator implements TokenEstimator {

    private static final int CJK_TOKEN = 1;
    private static final double ASCII_TOKEN = 0.25;

    @Override
    public int estimate(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String s = text.toString();
        double acc = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            acc += isCjk(cp) ? CJK_TOKEN : ASCII_TOKEN;
        }
        return (int) Math.ceil(acc);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF)      // CJK 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // CJK 统一表意
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK 兼容
                || (cp >= 0x20000 && cp <= 0x3134F) // CJK 扩展 B+
                || (cp >= 0x3040 && cp <= 0x30FF)  // 日文假名
                || (cp >= 0xAC00 && cp <= 0xD7AF); // 韩文
    }
}