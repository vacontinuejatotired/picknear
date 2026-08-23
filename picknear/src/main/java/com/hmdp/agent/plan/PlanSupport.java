package com.hmdp.agent.plan;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.observability.model.CallerType;
import com.hmdp.agent.plan.model.PlanRequest;
import com.hmdp.agent.plan.model.ValidationOptions;
import com.hmdp.agent.plan.support.PlanParser;
import com.hmdp.agent.plan.support.PlanValidator;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.util.TextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划流水线共享支撑（两策略共用的编排原语）。
 * <p>
 * 承担：规划 LLM 调用（渲染系统/用户模板 + 注入 userId）、历史摘要构建、解析+校验的组合入口。
 * </p>
 */
@Slf4j
@Component
public class PlanSupport {

    public static final int PLAN_RESPONSE_TRUNCATE = 200;
    public static final int RESULT_SUMMARY_LEN = 50;

    private final ChatClient chatClient;
    private final PromptService promptService;
    private final PlanParser planParser;
    private final PlanValidator planValidator;

    public PlanSupport(@Qualifier("aliibabaChatClient") ChatClient chatClient,
                       PromptService promptService,
                       PlanParser planParser,
                       PlanValidator planValidator) {
        this.chatClient = chatClient;
        this.promptService = promptService;
        this.planParser = planParser;
        this.planValidator = planValidator;
    }

    /** 解析 + 校验的组合入口（Phase1 直解 / 规划 LLM 结果统一走这里） */
    public List<SubTask> parseAndValidate(PlanRequest req, String raw, ValidationOptions opts) {
        return planValidator.validate(planParser.parse(raw), req.toolCallbacks(), req.history(), opts);
    }

    /**
     * 渲染规划 prompt 并调用规划 LLM（失败降级返回 "[]"）。
     *
     * @param userTemplateKey 用户模板键（PLANNER_USER legacy / PLANNER_USER_V2 两级）
     */
    public String plannerCall(PlanRequest req, String toolsDesc, String userTemplateKey) {
        List<String> completedSummary = req.history().getCompleted().stream()
                .map(t -> t.getToolName() + ": " + truncate(String.valueOf(t.getResult()), RESULT_SUMMARY_LEN))
                .toList();
        List<String> failedSummary = req.history().getFailed().stream()
                .map(t -> t.getToolName() + ": " + extractErrorType(String.valueOf(t.getResult())))
                .toList();

        String userId = req.userId() != null ? String.valueOf(req.userId()) : "";
        Map<String, String> planVars = new LinkedHashMap<>();
        planVars.put("toolsDescription", toolsDesc);
        planVars.put("completedSummary", String.join("\n", completedSummary));
        planVars.put("failedSummary", String.join("\n", failedSummary));
        planVars.put("userInput", req.userInput());
        planVars.put("currentResponse", truncate(req.aiResponse(), PLAN_RESPONSE_TRUNCATE));
        planVars.put("planStart", PlanParser.PLAN_START);
        planVars.put("planEnd", PlanParser.PLAN_END);
        planVars.put("userId", userId);

        ChatModelObservationConventionConfig.mark(CallerType.PLANNER);
        try {
            try {
                String result = chatClient.prompt()
                        .system(promptService.render(PromptKeys.SYSTEM_PLANNER, Map.of("userId", userId)))
                        .user(promptService.render(userTemplateKey, planVars))
                        .call().content();
                log.info("  [规划] AI 建议: {}", result);
                return result;
            } finally {
                ChatModelObservationConventionConfig.clear();
            }
        } catch (Exception e) {
            log.warn("AI 规划请求失败", e);
            return "[]";
        }
    }

    /** 截取前 N 个码点，超长加 "..."（codepoint-safe，见 TextUtils） */
    public static String truncate(String s, int max) {
        return TextUtils.truncate(s, max);
    }

    /** 从异常信息中提取错误类型首行 */
    private static String extractErrorType(String error) {
        if (error == null) {
            return "未知错误";
        }
        String[] lines = error.split("\n");
        String first = lines[0];
        return first.length() > 80 ? first.substring(0, 80) : first;
    }
}
