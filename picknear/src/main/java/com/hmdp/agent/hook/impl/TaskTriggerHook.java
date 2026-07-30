package com.hmdp.agent.hook.impl;

import com.hmdp.agent.hook.AfterAiHook;
import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.hook.HookResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 触发词检测 Hook。
 * <p>
 * 检测用户输入是否包含"对比/总结/分析/统计/归纳"等需要拆解规划的触发词，
 * 返回 PLANNING 进入 TaskPlanner。
 * </p>
 *
 * <pre>
 * 设计约束：不超过 15 行（不含类声明和常量定义）
 * </pre>
 */
@Component
public class TaskTriggerHook implements AfterAiHook {

    private static final List<String> TRIGGERS = List.of(
            "对比", "总结", "分析", "统计", "归纳", "报告",
            "比较", "差异", "变化", "趋势", "分别"
    );

    @Override
    public HookResult afterAi(String input, String response, ChatContext ctx) {
        if (response == null || response.length() < 20
                || response.contains("无法") || response.contains("不能") || response.contains("抱歉")) {
            return HookResult.pass();
        }
        for (String kw : TRIGGERS) {
            if (input.contains(kw)) return HookResult.planningRequired();
        }
        return HookResult.pass();
    }
}
