package com.hmdp.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.agent.dto.ConversationVO;
import com.hmdp.agent.entity.AgentConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentConversationMapper extends BaseMapper<AgentConversation> {

    /**
     * 会话列表：按活跃时间倒序 + 消息数（LEFT JOIN 聚合，避免 N+1）。
     * GROUP BY 含所有非聚合列，兼容 ONLY_FULL_GROUP_BY。
     */
    @Select("""
            SELECT c.conversation_id, c.title, MAX(c.updated_at) AS updated_at,
                   COUNT(m.id) AS message_count
            FROM agent_conversation c
            LEFT JOIN agent_message m ON m.conversation_id = c.conversation_id
            WHERE c.user_id = #{userId} AND c.status = 0
            GROUP BY c.conversation_id, c.title
            ORDER BY updated_at DESC
            """)
    List<ConversationVO> selectConversationList(@Param("userId") Long userId);
}
