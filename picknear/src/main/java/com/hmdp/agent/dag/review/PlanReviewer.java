package com.hmdp.agent.dag.review;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工具规划审查器接口（预留）
 * 
 * <p>用于审查 LLM 选择的工具列表是否合理。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
public interface PlanReviewer {
    
    /**
     * 审查工具规划
     * 
     * @param selectedTools LLM 选择的工具列表
     * @param userInput     用户输入
     * @return 审查结果
     */
    ReviewResult review(List<String> selectedTools, String userInput);
    
    /**
     * 审查结果
     */
    @Data
    @Builder
    class ReviewResult {
        /** 是否通过 */
        private boolean approved;
        
        /** 审查意见 */
        private String reason;
        
        /** 建议修改的工具列表（可选） */
        private List<String> suggestedTools;
    }
}
