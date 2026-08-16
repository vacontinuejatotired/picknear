package com.hmdp.user.account;

import cn.hutool.core.util.RandomUtil;
import com.hmdp.user.entity.User;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.user.service.IUserService;
import com.hmdp.utils.constants.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 账号服务 — 账号查询/自动注册/密码更新（user 域收敛，P2-S5）
 * <p>
 * 职责边界（自 UserServiceImpl 迁出，行为等价）：
 * <ul>
 *   <li>queryByPhone：手机号查账号（登录/重置共用）</li>
 *   <li>createUser：自动注册（User 记录 + 同步 UserInfo 记录，nickName 迁移至此）</li>
 *   <li>getById / updatePassword：账号读写</li>
 * </ul>
 * 登录策略（auth.login）只调不建，避免注册逻辑散落。
 * </p>
 */
@Slf4j
@Component
public class UserAccountService {

    @Resource
    private IUserService userService;
    @Resource
    private IUserInfoService userInfoService;

    /** 手机号查账号 */
    public User queryByPhone(String phone) {
        return userService.query().eq("phone", phone).one();
    }

    /** 按 ID 查账号 */
    public User getById(Long userId) {
        return userService.getById(userId);
    }

    /**
     * 自动注册：创建 User 记录 + 同步创建 UserInfo 记录（nickName 已迁移至此）。
     * 密码登录/验证码登录共用，消除重复注册逻辑。
     *
     * @param encodedPassword 加密后的密码；验证码注册传 null（无密码）
     */
    public User createUser(String phone, String encodedPassword) {
        String nickName = SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6);
        User user = new User().setPhone(phone)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        if (encodedPassword != null) {
            user.setPassword(encodedPassword);
        }
        userService.save(user);
        // 同步创建 UserInfo 记录
        UserInfo newInfo = new UserInfo();
        newInfo.setUserId(user.getId());
        newInfo.setNickName(nickName);
        userInfoService.save(newInfo);
        log.info("新用户已创建 phone={}, userId={}", phone, user.getId());
        return user;
    }

    /** 更新密码（含 updateTime） */
    public void updatePassword(User user, String encodedPassword) {
        user.setPassword(encodedPassword);
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);
    }
}
