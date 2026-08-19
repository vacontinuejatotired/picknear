package com.hmdp.agent.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.agent.prompt.entity.LocalPrompt;
import org.apache.ibatis.annotations.Mapper;

/**
 * 本地提示词 Mapper
 */
@Mapper
public interface LocalPromptMapper extends BaseMapper<LocalPrompt> {
}
