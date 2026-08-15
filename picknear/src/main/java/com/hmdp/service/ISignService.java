package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 签到服务 — Redis BitMap 签到与连续签到统计。
 * <p>
 * 从 IUserService 拆出：签到是用户行为，与登录/凭证（认证域）无关。
 * </p>
 */
public interface ISignService {

    /** 今日签到（按年月建 BitMap key，bit 位 = 日） */
    Result sign();

    /** 连续签到天数统计（从今天往前数连续为 1 的天数） */
    Result getSignCount();
}
