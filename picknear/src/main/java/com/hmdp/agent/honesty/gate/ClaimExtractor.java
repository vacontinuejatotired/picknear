package com.hmdp.agent.honesty.gate;

import java.util.List;

/**
 * 断言抽取端口（反编造 L3）：从 summary 提取可锚定断言。
 */
public interface ClaimExtractor {

    List<Claim> extract(String text);
}
