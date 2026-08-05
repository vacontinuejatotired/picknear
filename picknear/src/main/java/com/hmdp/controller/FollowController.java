package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 关注控制器 — 关注/取关、是否已关注、共同关注查询
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
@Tag(name = "关注模块", description = "关注、取关、共同关注查询接口")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isfollow}")
    @Operation(summary = "关注/取关用户", description = "关注或取消关注指定用户")
    public Result followUser(
            @Parameter(description = "目标用户ID") @PathVariable Long id,
            @Parameter(description = "是否关注") @PathVariable Boolean isfollow) {
        return followService.follow(id, isfollow);
    }

    @GetMapping("/or/not/{targetId}")
    @Operation(summary = "查询是否已关注", description = "检查当前用户是否关注了指定用户")
    public Result queryFollowStatus(
            @Parameter(description = "目标用户ID") @PathVariable("targetId") Long targetId) {
                if (targetId == null) {
                    return Result.fail("目标用户ID不能为空");
                }

        return followService.queryFollowStatus(targetId);
    }

    @GetMapping("/common/{id}")
    @Operation(summary = "查询共同关注", description = "获取当前用户与指定用户的共同关注列表")
    public Result queryCommonFollow(
            @Parameter(description = "目标用户ID") @PathVariable("id") Long id) {
        return followService.queryCommonFollow(id);
    }
}
