package com.hmdp.mapper;

import com.hmdp.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper — MyBatis-Plus 基础 CRUD + 自定义 XML 查询
 */
public interface UserMapper extends BaseMapper<User> {

    User getUser( User tempUser);
}
