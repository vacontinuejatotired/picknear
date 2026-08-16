package com.hmdp.content.service;

import com.hmdp.dto.Result;
import com.hmdp.content.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

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

    /** 某用户关注列表（agent 工具用，M-4 对齐） */
    List<Follow> listFollowsByUserId(Long userId);
}
