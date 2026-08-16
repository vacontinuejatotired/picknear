package com.hmdp.user.query;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.common.cache.CacheManager;
import com.hmdp.dto.Result;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.user.entity.User;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 用户查询服务 — 用户公开信息查询（缓存穿透防护 + VO 装配，user 域收敛，P2-S6）
 * <p>
 * 自 UserController.queryUserById 迁出（行为等价）；缓存经 {@link CacheManager}
 * （P4-S3 替换 CacheClient）；nickName/icon 自 tb_user_info 补查装配。
 * </p>
 */
@Slf4j
@Component
public class UserQueryService {

    @Resource
    private CacheManager cacheManager;
    @Resource
    private IUserService userService;
    @Resource
    private IUserInfoService userInfoService;

    /** 根据用户 ID 查询公开信息（缓存穿透防护；不存在返回 ok(null)） */
    public Result queryUserById(Long userId) {
        // 缓存查询（缓存穿透防护）
        User user = cacheManager.queryWithCache(userId, User.class, "cache:user:",
                id -> userService.getById(id), 30L, TimeUnit.MINUTES);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // nickName、icon 已迁移到 tb_user_info，需要额外查询
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo != null) {
            userDTO.setNickName(userInfo.getNickName());
            userDTO.setIcon(userInfo.getIcon());
        }
        return Result.ok(userDTO);
    }

    /** 用户详情（仅自己可查；时间戳置空） */
    public UserInfo queryInfoDetail(Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info != null) {
            info.setCreateTime(null);
            info.setUpdateTime(null);
        }
        return info;
    }
}
