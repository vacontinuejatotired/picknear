package com.hmdp.user.entity;

import com.hmdp.user.dto.UserDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户信息缓存实体 — Caffeine本地缓存中存储的用户信息快照
 * <p>
 * D4 修复（2026-08）：原实现 {@code extends UserDTO} 又重声明相同字段
 * （id/nickName/icon），导致字段遮蔽 + {@code @EqualsAndHashCode(callSuper=true)}
 * 语义混乱。现改为直接继承 UserDTO 字段，构造显式委托 super。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserinfoCache extends UserDTO {

    public UserinfoCache() {
    }

    public UserinfoCache(Long id, String nickName, String icon) {
        super(id, nickName, icon);
    }
}
