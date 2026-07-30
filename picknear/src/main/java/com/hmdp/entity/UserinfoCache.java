package com.hmdp.entity;

import com.hmdp.dto.UserDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 用户信息缓存实体 — Caffeine本地缓存中存储的用户信息快照
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserinfoCache extends UserDTO {
    private Long id;
    private String nickName;
    private String icon;

}
