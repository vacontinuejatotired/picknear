#!/usr/bin/env sh
# doc_sync_hook.sh — Claude Code hook 统一入口包装。
#
# 用法：sh doc_sync_hook.sh <同目录下的 py 脚本名> [args...]   （stdin 原样透传）
#
# 解释器要求：Python >= 3.8（脚本仅用标准库；P0b 由 AST 核对确认无 3.10+ 专属语法）。
# 探测顺序 python3 → python → py -3，取第一个存在者；全部不可用或版本过低 → 静默 exit 0。
#
# 设计要点（P0a 实测）：
# - 一律经 `sh` 显式启动：hgfs 共享目录不保证执行位。
# - 本文件必须以 LF 提交（.gitattributes 强制）——CRLF 会破坏 shebang 与 sh 语法。
# - fail-open：本包装不阻断会话。版本过低会写一条 events 日志（若可写），并静默退出。
set -u

if [ "$#" -lt 1 ]; then
  exit 0
fi

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 0
TARGET="$DIR/$1"
shift

# 强制 UTF-8 输出，避免 Windows 侧 GBK 默认编码破坏中文路径/文案
: "${PYTHONUTF8:=1}"
export PYTHONUTF8
: "${PYTHONIOENCODING:=utf-8}"
export PYTHONIOENCODING

# 最低版本守卫：过低则静默退出（脚本自身在 3.8- 上可能语法/行为异常）
MIN_MAJOR=3
MIN_MINOR=8

_pick() {
  # $1 = 候选解释器命令（可含空格，如 "py -3"）
  command -v ${1%% *} >/dev/null 2>&1 || return 1
  $1 -c "import sys; sys.exit(0 if sys.version_info[:2] >= ($MIN_MAJOR, $MIN_MINOR) else 1)" \
    >/dev/null 2>&1 || return 1
  return 0
}

if _pick "python3"; then
  exec python3 "$TARGET" "$@"
elif _pick "python"; then
  exec python "$TARGET" "$@"
elif _pick "py -3"; then
  exec py -3 "$TARGET" "$@"
else
  # 无可用且版本达标的解释器：记一条日志（尽力而为），静默退出以 fail-open。
  if [ -n "${CLAUDE_PROJECT_DIR:-}" ]; then
    LOGDIR="$CLAUDE_PROJECT_DIR/.claude/doc-sync"
    if mkdir -p "$LOGDIR" 2>/dev/null; then
      printf '{"ts":"%s","event":"error","where":"hook_wrapper","message":"no python >= %s.%s available"}\n' \
        "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$MIN_MAJOR" "$MIN_MINOR" \
        >> "$LOGDIR/events-wrapper.jsonl" 2>/dev/null || true
    fi
  fi
  exit 0
fi
