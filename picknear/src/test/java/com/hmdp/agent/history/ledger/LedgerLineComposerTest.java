package com.hmdp.agent.history.ledger;

import com.hmdp.agent.execution.model.ToolEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LedgerLineComposer — 账本行合成单测（真值→单行、换行折叠、超长截断、空值忽略）。
 */
class LedgerLineComposerTest {

    @Test
    void should_compose_single_line_with_tool_prefix() {
        String line = LedgerLineComposer.compose(
                ToolEvidence.inline("queryShopById", "茶颜悦色 人均35 评分4.8"));
        assertThat(line).isEqualTo("queryShopById: 茶颜悦色 人均35 评分4.8");
    }

    @Test
    void should_collapse_newlines_into_single_line() {
        String line = LedgerLineComposer.compose(
                ToolEvidence.inline("queryTotalBlogs", "共 12 篇\n点赞 5\n"));
        assertThat(line).isEqualTo("queryTotalBlogs: 共 12 篇 点赞 5");
    }

    @Test
    void should_truncate_overlong_raw() {
        String longRaw = "数".repeat(500);
        String line = LedgerLineComposer.compose(ToolEvidence.inline("q", longRaw));
        assertThat(line).startsWith("q: 数").endsWith("…");
        assertThat(line.length()).isLessThanOrEqualTo(205);
    }

    @Test
    void should_ignore_null_or_blank() {
        assertThat(LedgerLineComposer.compose(null)).isNull();
        assertThat(LedgerLineComposer.compose(ToolEvidence.inline(null, "x"))).isNull();
        assertThat(LedgerLineComposer.compose(ToolEvidence.inline("q", "  "))).isEqualTo("q: ");
    }
}
