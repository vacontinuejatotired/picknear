#!/usr/bin/env python3
"""doc_sync_record.py — PostToolUse 记录器（Edit/Write/NotebookEdit → edits.jsonl）。

契约（P0a 实证结论，见 md/ops/文档同步运维手册.md）：
- **恒定无 stdout 输出**，恒 exit 0。
  P0a 实测：PostToolUse 上的额外输出会被 harness 注入（`hook additional context`
  或 `hook blocking error`），使"回合级提醒"退化成"编辑级刷屏"。一切用户/模型可见
  文案只由 doc_sync_stop.py 产出。
- <0.1s 完成；不调用 git（git 调用集中在 Stop 侧判定时做一次）。
- DOC_SYNC_DISABLE=1 → 直接退出（脚本级紧急开关）。

已知盲区（计划 §5.2 明示）：Bash 重定向/脚本写文件、MCP 文件工具、手动编辑器改动
不进本记录——由 CI 层基于 git 事实兜底。
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import doc_sync_lib as lib  # noqa: E402


def main() -> int:
    if os.environ.get("DOC_SYNC_DISABLE") == "1":
        return 0

    data = lib.read_stdin_json()
    if not data:
        err = lib.last_stdin_error()
        if err and err != "empty stdin":
            lib.log_error(os.environ.get("CLAUDE_PROJECT_DIR"), "record_stdin", err)
        return 0

    tool_input = data.get("tool_input")
    file_path = ""
    if isinstance(tool_input, dict):
        raw = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
        file_path = str(raw)

    if not file_path:
        return 0

    project_dir = os.environ.get("CLAUDE_PROJECT_DIR")
    record = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "session_id": data.get("session_id"),
        "tool_name": data.get("tool_name"),
        "file_path": file_path,          # 原样保留（Windows 反斜杠），消费侧归一化
        "normalized": lib.normalize_path(file_path),
    }
    lib.append_jsonl(lib.state_dir(project_dir) / "edits.jsonl", record)
    return 0


if __name__ == "__main__":
    sys.exit(main())
