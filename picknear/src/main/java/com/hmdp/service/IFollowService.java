package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * 关注服务接口 — 关注/取关、共同关注查询
 */
public interface IFollowService extends IService<Follow> {

    Result queryFollowStatus(Long targetId);

    Result follow(Long id, Boolean isfollow);

    Result queryCommonFollow(Long id);
}
