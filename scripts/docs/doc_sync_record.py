"""doc_sync_record.py — PostToolUse 记录器（Claude Edit/Write/NotebookEdit、Codex apply_patch）。

契约（P0a 实证结论，见 md/ops/文档同步运维手册.md）：
- **恒定无 stdout 输出**，恒 exit 0。
  P0a 实测：PostToolUse 上的额外输出会被 harness 注入（`hook additional context`
  或 `hook blocking error`），使"回合级提醒"退化成"编辑级刷屏"。一切用户/模型可见
  文案只由 doc_sync_stop.py 产出。
- <0.1s 完成；不调用 git（git 调用集中在 Stop 侧判定时做一次）。
- DOC_SYNC_DISABLE=1 → 直接退出（脚本级紧急开关）。

跨 harness 兼容（2026-10-12 实测，见运维手册 §3.4）：
- Claude Code：tool_input.file_path 直接给出目标文件。
- Codex：工具名是 apply_patch，tool_input 只有整段 patch 文本（command），没有 file_path；
  需从 "*** Add|Update|Delete File: <path>" 解析目标路径。解析不出路径时静默丢弃，
  不猜、不弱归属（宁可漏记，不可错记）。
- Codex 项目级 hook 不注入 CLAUDE_PROJECT_DIR，但 payload 带 cwd；record 把它一并落盘，
  供 Stop 侧在相对路径场景下正确归属。

已知盲区（计划 §5.2 明示）：Bash 重定向/脚本写文件、MCP 文件工具、手动编辑器改动
不进本记录——由 CI 层基于 git 事实兜底。
"""

from __future__ import annotations

import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import doc_sync_lib as lib  # noqa: E402


# Codex apply_patch 的路径行；只认字面量，不猜测。
_PATCH_PATH_RE = re.compile(r"^\*\*\* (?:Add|Update|Delete) File: (.+?)\s*$", re.MULTILINE)


def extract_patch_paths(command: str) -> list:
    """从 Codex apply_patch 的 patch 文本解析目标文件路径（按出现顺序去重）。

    解析不出返回空列表——调用方负责静默丢弃（不弱归属）。
    """
    if not isinstance(command, str) or not command:
        return []
    seen = set()
    out = []
    for raw in _PATCH_PATH_RE.findall(command):
        path = raw.strip().strip('"')
        if path and path not in seen:
            seen.add(path)
            out.append(path)
    return out


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
    raw_paths = []
    if isinstance(tool_input, dict):
        direct = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
        if direct:
            raw_paths.append(str(direct))
        else:
            raw_paths.extend(extract_patch_paths(str(tool_input.get("command") or "")))

    if not raw_paths:
        return 0

    project_dir = os.environ.get("CLAUDE_PROJECT_DIR")
    cwd = data.get("cwd")            # Codex 项目 hook 不注入环境变量，但 payload 带 cwd
    ts = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    target = lib.state_dir(project_dir) / "edits.jsonl"
    for file_path in raw_paths:
        record = {
            "ts": ts,
            "session_id": data.get("session_id"),
            "tool_name": data.get("tool_name"),
            "file_path": file_path,
            "normalized": lib.normalize_path(file_path),
        }
        if cwd:
            record["cwd"] = cwd              # 仅在存在时记录，不改动既有字段契约
        lib.append_jsonl(target, record)
    return 0


if __name__ == "__main__":
    sys.exit(main())
