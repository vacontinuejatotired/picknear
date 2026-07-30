package com.hmdp.dto;

import lombok.Data;

/**
 * 编辑个人资料请求 DTO — 所有字段均为可选，只传需要修改的字段
 */
@Data
public class ProfileUpdateDTO {
    private String nickName;
    private String icon;
    private String city;
    private String introduce;
}
