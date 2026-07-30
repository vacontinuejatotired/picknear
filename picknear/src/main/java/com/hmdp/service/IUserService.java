package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.PasswordChangeDTO;
import com.hmdp.dto.ProfileUpdateDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.TokenPair;
import com.hmdp.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * 用户服务接口 — 登录/注册、验证码、签到、Token生成
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);
    TokenPair login(LoginFormDTO loginForm);

    /** 登出：删除 Redis 中该用户的所有 Token/Version */
    void logout(Long userId);

    TokenPair changePassword(PasswordChangeDTO dto);

    /** 编辑个人资料 — nickName/icon/city/introduce 均为可选 */
    Result updateProfile(ProfileUpdateDTO dto);

    /** 重置密码 — 验证码 + 新密码，免旧密码 */
    Result resetPassword(String phone, String code, String newPassword);

    Result sign();

    Result getSignCount();
}
