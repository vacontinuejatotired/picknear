package com.hmdp.agent.tool.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.content.entity.Follow;
import com.hmdp.content.service.IFollowService;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * UserQueryTool — 用户/关注查询工具测试。
 */
@ExtendWith(MockitoExtension.class)
class UserQueryToolTest {

    @Mock private IUserInfoService userInfoService;
    @Mock private IFollowService followService;
    @Mock private ToolContext toolContext;

    private UserQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new UserQueryTool();
        ReflectionTestUtils.setField(tool, "userInfoService", userInfoService);
        ReflectionTestUtils.setField(tool, "followService", followService);
    }

    @Test
    void queryUserProfile_should_return_public_fields() {
        UserInfo info = new UserInfo();
        info.setUserId(3L);
        info.setNickName("张三");
        info.setCity("上海");
        info.setIntroduce("探店博主");
        info.setFans(100);
        info.setFollowee(20);
        info.setCredits(50);
        when(userInfoService.getById(3L)).thenReturn(info);

        UserQueryTool.UserProfileBrief result = tool.queryUserProfile(3L);

        assertThat(result).as("应返回用户公开资料").isNotNull();
        assertThat(result.nickName()).isEqualTo("张三");
        assertThat(result.city()).isEqualTo("上海");
        assertThat(result.fans()).isEqualTo(100);
    }

    @Test
    void queryUserProfile_should_return_null_when_not_found() {
        when(userInfoService.getById(999L)).thenReturn(null);

        UserQueryTool.UserProfileBrief result = tool.queryUserProfile(999L);

        assertThat(result).as("查不到应返回 null").isNull();
    }

    @Test
    void queryMyFollows_should_return_followed_users_with_nickname() {
        when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        when(followService.listFollowsByUserId(1L))
                .thenReturn(List.of(new Follow().setUserId(1L).setFollowUserId(3L)));

        UserInfo followed = new UserInfo();
        followed.setUserId(3L);
        followed.setNickName("张三");
        when(userInfoService.listByIds(anyCollection())).thenReturn(List.of(followed));

        List<UserQueryTool.FollowBrief> result = tool.queryMyFollows(toolContext);

        assertThat(result).as("应返回关注列表").hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(3L);
        assertThat(result.get(0).nickName()).isEqualTo("张三");
    }

    @Test
    void queryMyFollows_should_return_empty_when_no_follows() {
        when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        when(followService.listFollowsByUserId(1L)).thenReturn(List.of());

        List<UserQueryTool.FollowBrief> result = tool.queryMyFollows(toolContext);

        assertThat(result).as("无关注应返回空列表").isEmpty();
    }
}
