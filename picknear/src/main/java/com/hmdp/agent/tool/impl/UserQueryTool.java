package com.hmdp.agent.tool.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.entity.Follow;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserInfoService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 用户/关注查询工具 — 用户公开资料 / 我的关注列表。
 * 供长任务串链：用户 → 资料 / 关注列表。
 */
@TargetTool(active = true)
@Slf4j
public class UserQueryTool {

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private IFollowService followService;

    public record UserProfileBrief(Long userId, String nickName, String city, String introduce,
                                   Integer fans, Integer followee, Integer credits) {}

    public record FollowBrief(Long userId, String nickName) {}

    /**
     * 查询用户公开资料（不暴露手机号等隐私字段）。
     */
    @Tool(description = """
            查询用户的公开资料（昵称/城市/简介/粉丝数/关注数/积分），和「这个作者是谁」「用户资料」「个人信息」一起使用。
            返回的昵称可与博客作者、评论用户对上。不返回手机号等隐私字段。
            """)
    public UserProfileBrief queryUserProfile(
            @ToolParam(description = "用户ID") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) return null;
        return new UserProfileBrief(info.getUserId(), info.getNickName(), info.getCity(),
                info.getIntroduce(), info.getFans(), info.getFollowee(), info.getCredits());
    }

    /**
     * 查询当前用户自己关注的用户列表（批量取昵称，避免 N+1）。
     */
    @Tool(description = """
            查询当前用户自己关注的用户列表，和「我的关注」「我关注了谁」「关注列表」一起使用。
            返回被关注用户ID和昵称。只看当前登录用户自己的关注关系。
            """)
    public List<FollowBrief> queryMyFollows(ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        log.info("queryMyFollows userId: {}", userId);
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        if (follows.isEmpty()) return List.of();

        List<Long> ids = follows.stream().map(Follow::getFollowUserId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nickNames = new HashMap<>();
        if (!ids.isEmpty()) {
            userInfoService.listByIds(ids).forEach(i -> nickNames.put(i.getUserId(), i.getNickName()));
        }
        return follows.stream()
                .map(f -> new FollowBrief(f.getFollowUserId(),
                        nickNames.getOrDefault(f.getFollowUserId(), "")))
                .toList();
    }
}
