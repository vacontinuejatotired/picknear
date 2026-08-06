package com.hmdp.agent.prompt;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板渲染器：{@code {{var}}} 占位符替换。
 * <p>
 * Fail-Open 到底（P0）：模板为 null → 返回空串；变量缺失 → 保留字面量 + WARN
 * （缺失的占位符会原样出现在 Langfuse trace 的 model input 里，比静默替换为空好调试）；
 * 变量值含 {@code $} / {@code \} 时用 {@link Matcher#quoteReplacement} 防误替换。永不抛异常。
 * </p>
 */
@Slf4j
public final class PromptRenderer {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)\\s*\\}\\}");

    private PromptRenderer() {}

    public static String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        Matcher matcher = VAR.matcher(template);
        StringBuffer sb = new StringBuffer(template.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = vars.get(name);
            if (value == null) {
                log.warn("[prompt] 变量缺失，保留字面量占位: {}", name);
                matcher.appendReplacement(sb, matcher.group(0));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
