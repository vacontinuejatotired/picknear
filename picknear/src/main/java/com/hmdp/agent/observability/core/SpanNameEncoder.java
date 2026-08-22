package com.hmdp.agent.observability.core;

import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.util.TextUtils;
import org.springframework.stereotype.Component;

/**
 * span 名载荷编码器（评审 13.3.2：从业务类 {@code ToolGuardGate} 下沉的加工逻辑）。
 * <p>
 * 职责：把 guard 的<b>原始语义</b>（decision/toolName/modelName/payload）编码成 span 名后缀。
 * 封装三件事：语义拼接格式（{@code decision.toolName[.modelName][.紧凑参数]}）、参数紧凑化
 * （去引号/空白 → 统一脱敏 → 限长）、整体清洗（去控制字符/空白 → 限长）。
 * </p>
 * <p>
 * 边界（观测后端解耦改造方案 §2.2 铁律）：<b>语义内容由业务侧传出、加工格式在本类完成</b>——
 * 业务代码不再持有任何 span 名拼接/载荷加工逻辑；换观测后端时编码与否由命名策略决定，
 * 本类只提供"怎么拼"。
 * </p>
 */
@Component
public class SpanNameEncoder {

    /** 紧凑参数入 span 名的最大字符数（防 span 名膨胀；历史值不变，保留观测口径） */
    public static final int ARGS_MAX_CHARS = 40;

    /** span 名整体最大长度（含类型前缀与语义后缀） */
    public static final int NAME_MAX_CHARS = 96;

    private final AttributeSanitizer sanitizer;

    public SpanNameEncoder(AttributeSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * 编码 guard span 语义后缀：{decision}.{toolName}[.{modelName}][.{紧凑参数}]。
     * 模型名与参数均为可选项（null/空/无法紧凑时省略对应段）。
     *
     * @return 拼好的语义后缀（不含 agent.guard. 前缀）；决策或工具名为空时返回 null
     */
    public String encodeGuardSemantic(String decision, String toolName, String modelName, String payload) {
        if (decision == null || decision.isBlank() || toolName == null || toolName.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(decision).append('.').append(toolName);
        if (modelName != null && !modelName.isBlank()) {
            sb.append('.').append(modelName);
        }
        String args = compactArgs(payload);
        if (!args.isBlank()) {
            sb.append('.').append(args);
        }
        return sanitizeName(sb.toString());
    }

    /**
     * 工具参数 → 紧凑摘要：去 JSON 引号/空白 → 统一脱敏（手机号/邮箱/身份证）→ 限长。
     * 空/null 参数或 {@code {}} 返回 ""（省略该段）。
     */
    public String compactArgs(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        String cleaned = payload.replaceAll("[\\p{Cntrl}\\s\"]", "");
        if (cleaned.isBlank() || "{}".equals(cleaned)) {
            return "";
        }
        String masked = sanitizer != null ? sanitizer.sanitizeSummary(cleaned) : cleaned;
        return TextUtils.truncate(masked, ARGS_MAX_CHARS);
    }

    /** span 名整体清洗：去空白/控制字符（防注入与膨胀），整体限长 */
    public String sanitizeName(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.replaceAll("[\\p{Cntrl}\\s]", "");
        return cleaned.length() <= NAME_MAX_CHARS ? cleaned : cleaned.substring(0, NAME_MAX_CHARS);
    }
}