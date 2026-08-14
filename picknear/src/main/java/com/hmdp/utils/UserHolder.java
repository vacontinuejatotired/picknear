package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * 用户持有者 — 通过 ThreadLocal 存储当前登录用户信息（用户ID + UserDTO）
 */
@Slf4j
public class UserHolder {
    private static final ThreadLocal<Long> tl = new ThreadLocal<>();

    private static final ThreadLocal<UserDTO> userDTOThreadLocal = new ThreadLocal<>();
    public static void saveUserId(Long userId){
        tl.set(userId);
    }

    public static Long getUserId(){
        if(tl.get() == null){
            log.warn("getUserId 被调用但 ThreadLocal 为空，调用栈如下：", new RuntimeException("debug stack"));
            throw new IllegalArgumentException("用户ID不存在");
        }
        return tl.get();
    }

    public static UserDTO getUserDTO(){
        return userDTOThreadLocal.get();
    }
    public static void saveUserDTO(UserDTO userDTO){
        userDTOThreadLocal.set(userDTO);
    }
    public static void remove(){
        tl.remove();
        userDTOThreadLocal.remove();
    }
}
