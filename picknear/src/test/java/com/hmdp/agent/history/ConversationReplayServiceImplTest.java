package com.hmdp.agent.history;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.agent.config.ReplayProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.mapper.AgentMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationReplayServiceImpl — 多轮记忆回放契约服务单测。
 * <p>
 * 纯 Mock（禁真 DB）；role→Message 映射行为归 AgentRoleReplayMessageMapperTest，此处不测映射细节。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ConversationReplayServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String CONV_ID = "conv-replay-001";
    private static final int WINDOW = 6;

    @Mock
    private AgentMessageMapper messageMapper;

    @Mock
    private ReplayProperties replayProperties;

    @Mock
    private ReplayMessageMapper replayMessageMapper;

    @Mock
    private ReplayBudgetTrim replayBudgetTrim;

    @InjectMocks
    private ConversationReplayServiceImpl replayService;

    @BeforeAll
    static void initMpTableInfo() {
        // 单测无 MyBatis 容器：播种 AgentMessage 的 TableInfo + lambda 列缓存，
        // 否则 LambdaQueryWrapper 的 AgentMessage::getId 等解析抛 "can not find lambda cache"
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentMessage.class);
    }

    @BeforeEach
    void setUp() {
        // 默认开启回放，单测聚焦窗口/过滤/fail-open 行为；disabled 用例单独覆盖
        lenient().when(replayProperties.isEnabled()).thenReturn(true);
        // 预算裁剪默认恒等（不裁），预算接线在 should_apply_char_budget_trim 单独验证
        lenient().when(replayBudgetTrim.trimToBudget(anyList(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void should_return_latest_window_in_ascending_order_and_apply_filters() {
        // 模拟 DB orderByDesc(id) 输出：最新在前
        List<AgentMessage> rows = List.of(
                msg(4L, "第4条"),
                msg(3L, "第3条"),
                msg(2L, "第2条"),
                msg(1L, "第1条"));
        when(messageMapper.selectList(any())).thenReturn(rows);
        when(replayMessageMapper.map(any())).thenAnswer(inv -> {
            AgentMessage r = inv.getArgument(0);
            return Optional.of(new UserMessage(r.getContent()));
        });

        List<Message> result = replayService.recentMessages(USER_ID, CONV_ID, WINDOW);

        // 尾部窗口 → reverse 升序（老→新）
        assertThat(result)
                .as("应升序返回最近历史（老在前）")
                .extracting(m -> ((UserMessage) m).getText())
                .containsExactly("第1条", "第2条", "第3条", "第4条");

        ArgumentCaptor<LambdaQueryWrapper<AgentMessage>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectList(captor.capture());
        LambdaQueryWrapper<AgentMessage> wrapper = captor.getValue();

        assertThat(wrapper.getSqlSegment())
                .as("应按会话+用户过滤")
                .contains("conversation_id")
                .contains("user_id");
        assertThat(wrapper.getExpression().getOrderBy().getSqlSegment())
                .as("应按主键倒序取最新在前")
                .contains("id");
        Object lastSql = ReflectionTestUtils.getField(wrapper, "lastSql");
        assertThat(((SharedString) lastSql).getStringValue())
                .as("尾部窗口 LIMIT 应为 2×windowTurns")
                .contains("LIMIT " + (2 * WINDOW));
    }

    @SuppressWarnings({"unchecked"})
    @Test
    void should_apply_char_budget_trim_after_reverse() {
        // mapper 返回视为「最新在前」（DB orderByDesc 语义）
        List<AgentMessage> rows = List.of(msg(2L, "新"), msg(1L, "老"));
        when(messageMapper.selectList(any())).thenReturn(rows);
        when(replayProperties.getMaxReplayChars()).thenReturn(3000);
        // 模拟裁剪器：只保留最新一条
        when(replayBudgetTrim.trimToBudget(anyList(), anyInt()))
                .thenAnswer(inv -> inv.<List<AgentMessage>>getArgument(0).subList(1, 2));
        when(replayMessageMapper.map(any())).thenAnswer(inv -> Optional.of(
                new UserMessage(((AgentMessage) inv.getArgument(0)).getContent())));

        List<Message> result = replayService.recentMessages(USER_ID, CONV_ID, WINDOW);

        assertThat(result)
                .as("应仅映射裁剪后保留的最新一条")
                .extracting(m -> ((UserMessage) m).getText())
                .containsExactly("新");
        verify(replayBudgetTrim).trimToBudget(anyList(), eq(3000));
    }

    @Test
    void should_return_empty_when_replay_disabled() {
        when(replayProperties.isEnabled()).thenReturn(false);

        List<Message> result = replayService.recentMessages(USER_ID, CONV_ID, WINDOW);

        assertThat(result).as("disabled 应跳过查询返回空").isEmpty();
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void should_return_empty_when_window_non_positive() {
        List<Message> result = replayService.recentMessages(USER_ID, CONV_ID, 0);

        assertThat(result).as("窗口≤0 应返回空").isEmpty();
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void should_return_empty_when_no_history() {
        when(messageMapper.selectList(any())).thenReturn(List.of());

        List<Message> result = replayService.recentMessages(USER_ID, CONV_ID, WINDOW);

        assertThat(result).as("空会话应返回空").isEmpty();
        verify(replayMessageMapper, never()).map(any());
    }

    @Test
    void should_fail_open_when_db_error() {
        when(messageMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        List<Message> result = replayService.recentMessages(USER_ID, CONV_ID, WINDOW);

        assertThat(result).as("DB 异常应 fail-open 返回空，不阻断对话").isEmpty();
    }

    private static AgentMessage msg(long id, String content) {
        return new AgentMessage()
                .setId(id)
                .setConversationId(CONV_ID)
                .setUserId(USER_ID)
                .setRole("user")
                .setContent(content);
    }
}