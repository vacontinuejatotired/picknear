#!/usr/bin/env python3
"""doc_sync_lib.py — 文档同步机制共享库（doc-map 加载、glob 匹配、归属、事件日志）。

设计约束（见 md/规范/文档同步规范.md）：
- 单一方言：glob 一律"仓库根相对 POSIX + gitignore 风格"，hook/CI/审计共用本模块的匹配器，禁止各写一套。
- 文件全集一律取 `git ls-files`（不用文件系统枚举——工作区有空目录残骸与非跟踪文件）。
- 路径一律先归一化 `\\`→`/`（Windows hook 传入的是反斜杠绝对路径）。
- 一切 git 子进程失败 → 降级为"未能判定" + 日志，不得把 git 报错当结论。
- 仅标准库；机制只在 Windows 宿主侧运行（VM 已转只读，不跑本机制）。

DOC_SYNC_HOOK_VERSION 供审计比对两仓库脚本是否漂移（计划 §11）。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
from pathlib import Path

DOC_SYNC_HOOK_VERSION = 1

VALID_TIERS = {"must", "should"}
VALID_MODES = {"observe", "enforce"}
VALID_HOOK_MODES = {"remind", "block-must"}


# --------------------------------------------------------------------------
# 路径归一化
# --------------------------------------------------------------------------

def normalize_path(raw: str) -> str:
    """把任意来源的路径归一为 POSIX 风格。

    Windows hook 传入 ``E:\\heima\\picknear\\x.py``；CI 传入 ``picknear/x.py``。
    统一为 ``/``，并去掉开头的 ``./``。
    """
    if not raw:
        return ""
    path = str(raw).replace("\\", "/")
    while path.startswith("./"):
        path = path[2:]
    return path


def sanitize_hostname() -> str:
    """hostname 用于日志文件名；过滤掉不安全字符。"""
    name = (socket.gethostname() or "unknown").strip() or "unknown"
    return re.sub(r"[^A-Za-z0-9._-]", "_", name)[:64]


# --------------------------------------------------------------------------
# 仓库定位
# --------------------------------------------------------------------------

def find_repo_root(start: Path) -> Path | None:
    """从 start（文件或其父目录）向上找最近的含 .git 的祖先。

    返回该祖先目录（仓库根），找不到返回 None。
    """
    try:
        current = start if start.is_dir() else start.parent
    except Exception:
        return None
    current = Path(os.path.abspath(str(current)))
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return None


# --------------------------------------------------------------------------
# doc-map 加载与校验
# --------------------------------------------------------------------------

class MapError(Exception):
    """doc-map 加载/校验失败。"""


def load_map(repo_root: Path) -> dict:
    """加载并做结构性校验（不碰 git）。失败抛 MapError。"""
    map_path = Path(repo_root) / "scripts" / "docs" / "doc-map.json"
    if not map_path.exists():
        raise MapError(f"doc-map.json not found: {map_path}")
    try:
        data = json.loads(map_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise MapError(f"doc-map.json parse error: {exc}") from exc

    if not isinstance(data, dict):
        raise MapError("doc-map.json must be a JSON object")
    if data.get("version") != 1:
        raise MapError(f"unsupported doc-map version: {data.get('version')!r}")
    if not str(data.get("repo") or "").strip():
        raise MapError("doc-map.repo is required")

    rules = data.get("rules")
    if not isinstance(rules, list) or not rules:
        raise MapError("doc-map.rules must be a non-empty list")

    seen: set[str] = set()
    for idx, rule in enumerate(rules):
        if not isinstance(rule, dict):
            raise MapError(f"rules[{idx}] must be an object")
        rid = str(rule.get("id") or "").strip()
        if not rid:
            raise MapError(f"rules[{idx}].id is required")
        if rid in seen:
            raise MapError(f"duplicate rule id: {rid}")
        seen.add(rid)

        tier = rule.get("tier")
        if tier not in VALID_TIERS:
            raise MapError(f"rule '{rid}': invalid tier {tier!r} (want {sorted(VALID_TIERS)})")

        if not _glob_list(rule.get("code")):
            raise MapError(f"rule '{rid}': code globs must be non-empty")

        for key in ("required_docs", "optional_docs"):
            value = rule.get(key)
            if value is not None and not isinstance(value, list):
                raise MapError(f"rule '{rid}': {key} must be a list")

    for key in ("orphan_exempt", "unmanaged", "exclude"):
        value = data.get(key)
        if value is not None and not isinstance(value, list):
            raise MapError(f"doc-map.{key} must be a list")

    return data


def _glob_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(v) for v in value if str(v).strip()]


def rule_doc_paths(rule: dict) -> list[str]:
    """一条规则涉及的全部文档路径（required + optional）。"""
    paths: list[str] = []
    for key in ("required_docs", "optional_docs"):
        for entry in rule.get(key) or []:
            if isinstance(entry, dict):
                path = str(entry.get("path") or "").strip()
            else:
                path = str(entry).strip()
            if path:
                paths.append(path)
    return paths


def required_doc_paths(rule: dict) -> list[str]:
    paths: list[str] = []
    for entry in rule.get("required_docs") or []:
        if isinstance(entry, dict):
            path = str(entry.get("path") or "").strip()
        else:
            path = str(entry).strip()
        if path:
            paths.append(path)
    return paths


# --------------------------------------------------------------------------
# glob 匹配（单一方言：仓库根相对 POSIX + gitignore 风格）
# --------------------------------------------------------------------------

def _glob_to_regex(pattern: str) -> re.Pattern:
    """把 gitignore 风格 glob 编译为正则。

    - ``**`` 匹配任意层级（含 0 层）
    - ``*``  匹配单层内任意字符（不跨 ``/``）
    - ``?``  匹配单层内一个字符
    - 无 ``/`` 结尾的模式按整串锚定匹配
    """
    pattern = normalize_path(pattern)
    out: list[str] = ["^"]
    i = 0
    n = len(pattern)
    while i < n:
        ch = pattern[i]
        if ch == "*":
            if i + 1 < n and pattern[i + 1] == "*":
                # ** 情况
                j = i + 2
                if j < n and pattern[j] == "/":
                    # "**/" 匹配零个或多个目录层级
                    out.append("(?:.*/)?")
                    i = j + 1
                else:
                    out.append(".*")
                    i = j
            else:
                out.append("[^/]*")
                i += 1
        elif ch == "?":
            out.append("[^/]")
            i += 1
        elif ch == "[":
            j = i + 1
            if j < n and pattern[j] in ("!", "^"):
                j += 1
            if j < n and pattern[j] == "]":
                j += 1
            while j < n and pattern[j] != "]":
                j += 1
            if j >= n:
                out.append(re.escape(ch))
                i += 1
            else:
                inner = pattern[i + 1:j]
                if inner.startswith("!"):
                    inner = "^" + inner[1:]
                out.append("[" + inner + "]")
                i = j + 1
        else:
            out.append(re.escape(ch))
            i += 1
    out.append("$")
    try:
        return re.compile("".join(out))
    except re.error:
        return re.compile(r"^\b$")  # 永不匹配


_GLOB_CACHE: dict[str, re.Pattern] = {}


def glob_match(path: str, pattern: str) -> bool:
    """单文件路径 vs 单 glob。path 需为仓库根相对 POSIX。"""
    key = pattern
    regex = _GLOB_CACHE.get(key)
    if regex is None:
        regex = _glob_to_regex(pattern)
        _GLOB_CACHE[key] = regex
    return bool(regex.match(normalize_path(path)))


def any_glob_match(path: str, patterns: list[str]) -> bool:
    return any(glob_match(path, p) for p in patterns)


def expand_globs(root: Path, patterns: list[str], tracked: list[str]) -> list[str]:
    """在 tracked（仓库根相对 POSIX 列表）中挑出命中任一 pattern 的文件。"""
    return [f for f in tracked if any_glob_match(f, patterns)]


# --------------------------------------------------------------------------
# git 事实
# --------------------------------------------------------------------------

def git_ls_files(repo_root: Path) -> tuple[list[str], str | None]:
    """返回 (仓库根相对 POSIX 路径列表, 错误信息)。

    **必须用 -z**：不带 -z 时 git 会对非 ASCII 路径做 C-quoting
    （`"md/agent/Agent\\344\\270\\212..."`），中文文档路径会全部变成转义串而匹配不上。
    -z 输出以 NUL 分隔的原始字节，不经 quoting，是最稳的取法。

    git 失败时返回 ([], 错误串) —— 调用方必须降级为"未能判定"，不得当结论。
    """
    code, out, err = _run_git_raw(repo_root, ["-c", "core.quotepath=false", "ls-files", "-z"])
    if code != 0:
        return [], (err or "git ls-files failed").strip()
    files = [
        normalize_path(chunk.decode("utf-8", errors="replace"))
        for chunk in out.split(b"\x00")
        if chunk.strip()
    ]
    return files, None


def git_last_commit_ts(repo_root: Path, path: str) -> int | None:
    """某路径末次提交的 unix 时间戳；失败/无记录返回 None。"""
    code, out, _ = _run_git(
        repo_root, ["log", "-1", "--format=%ct", "--", path]
    )
    if code != 0:
        return None
    text = out.strip()
    if not text:
        return None
    try:
        return int(text.splitlines()[0])
    except ValueError:
        return None


def _run_git(repo_root: Path, args: list[str]) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            ["git", *args],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=False,
        )
    except Exception as exc:
        return 1, "", f"{type(exc).__name__}: {exc}"
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def _run_git_raw(repo_root: Path, args: list[str]) -> tuple[int, bytes, str]:
    """字节级 git 调用（-z 输出用；避免文本层再引入编码/换行转换）。"""
    try:
        proc = subprocess.run(
            ["git", *args],
            cwd=str(repo_root),
            capture_output=True,
            timeout=30,
            check=False,
        )
    except Exception as exc:
        return 1, b"", f"{type(exc).__name__}: {exc}"
    stderr = (proc.stderr or b"").decode("utf-8", errors="replace")
    return proc.returncode, proc.stdout or b"", stderr


# --------------------------------------------------------------------------
# 判定核心
# --------------------------------------------------------------------------

def classify_files(repo_root: Path, doc_map: dict, files: list[str]) -> dict:
    """把仓库根相对 POSIX 文件列表分类。

    返回 {code: [...], docs: [...], unmanaged: [...], no_rule: [...], matched_rules: {rid: [...]}}

    **"文档编辑"的判定必须收窄**（P0b 实测踩坑）：
    不能简单地用 `endswith('.md')`——那会把工作区级文件（如 `.claude/doc-sync/HANDOFF.md`）
    和任何非本仓库文档体系的 .md 都算成"文档已同步"，从而**整体放行本次提醒**。
    实测后果：会话里碰过任意 .md，之后所有 Stop 全部走 skip_docs_edited，提醒永久失效。

    真正的"文档编辑"= 命中任一 rule 的 required_docs/optional_docs，
    或位于 `doc_root`（默认 `md/`）下的被跟踪 .md。
    """
    rules = doc_map.get("rules") or []
    unmanaged_globs = _glob_list(doc_map.get("unmanaged"))
    exclude_globs = _glob_list(doc_map.get("exclude"))
    doc_root = str(doc_map.get("doc_root") or "md").strip("/")

    # 所有规则登记的文档路径（精确匹配集）
    registered_docs: set[str] = set()
    for rule in rules:
        registered_docs.update(rule_doc_paths(rule))

    result: dict = {
        "code": [],
        "docs": [],
        "unmanaged": [],
        "no_rule": [],
        "matched_rules": {},
    }

    for path in files:
        if _is_doc_edit(path, doc_root, registered_docs):
            result["docs"].append(path)
            continue

        if any_glob_match(path, exclude_globs):
            continue

        if any_glob_match(path, unmanaged_globs):
            result["unmanaged"].append(path)
            continue

        hit_any = False
        for rule in rules:
            if any_glob_match(path, _glob_list(rule.get("code"))):
                result["matched_rules"].setdefault(str(rule.get("id")), []).append(path)
                hit_any = True

        if hit_any:
            result["code"].append(path)
        else:
            result["no_rule"].append(path)

    return result


def _is_doc_edit(path: str, doc_root: str, registered_docs: set[str]) -> bool:
    """该文件是否算"文档编辑"（会触发整体放行）。"""
    if path in registered_docs:
        return True
    if not path.lower().endswith(".md"):
        return False
    # 仅认本仓库文档根目录下的 .md（如 md/），工作区级/其它目录的 .md 不算
    root = doc_root.rstrip("/") + "/" if doc_root else ""
    return bool(root) and path.startswith(root)


def strongest_tier(doc_map: dict, rule_ids: list[str]) -> str | None:
    """一组规则里最强的 tier（must > should）。"""
    by_id = {str(r.get("id")): r for r in (doc_map.get("rules") or [])}
    tier = None
    for rid in rule_ids:
        rule = by_id.get(rid)
        if not rule:
            continue
        if rule.get("tier") == "must":
            return "must"
        tier = "should"
    return tier


def dedup_key(session_id: str, files: list[str], declared: bool) -> str:
    """内容键：同一状态反复出现时不再打扰（计划 §5.2 第 1.d 步）。"""
    payload = json.dumps(
        {
            "session": session_id or "",
            "files": sorted(set(files)),
            "declared": bool(declared),
        },
        ensure_ascii=True,
        sort_keys=True,
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


# --------------------------------------------------------------------------
# 声明检测
# --------------------------------------------------------------------------

DECLARATION_RE = re.compile(r"^Docs-Impact:\s*none\s*[—–-]\s*\S", re.IGNORECASE)


def detect_declaration(last_assistant_message: str | None) -> bool:
    """检测回复末个非空行是否为 ``Docs-Impact: none — <理由>``（理由非空）。

    锚定末行：防止多轮澄清后旧声明被复用误抑制（计划 §5.2 第 5.c 步）。
    """
    if not last_assistant_message:
        return False
    lines = [ln.strip() for ln in str(last_assistant_message).splitlines()]
    for line in reversed(lines):
        if not line:
            continue
        return bool(DECLARATION_RE.match(line))
    return False


# --------------------------------------------------------------------------
# 事件日志
# --------------------------------------------------------------------------

def state_dir(project_dir: str | None = None) -> Path:
    base = project_dir or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    return Path(base) / ".claude" / "doc-sync"


def append_jsonl(path: Path, record: dict) -> bool:
    """追加一行 JSON（LF）。失败静默返回 False——日志绝不阻断主流程。"""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        return True
    except Exception:
        return False


def log_event(project_dir: str | None, event: str, **fields) -> None:
    """写 events-<hostname>.jsonl（按 hostname 分文件，便于区分产生日志的机器）。"""
    record = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "event": event}
    record.update(fields)
    append_jsonl(state_dir(project_dir) / f"events-{sanitize_hostname()}.jsonl", record)


def log_error(project_dir: str | None, where: str, message: str) -> None:
    log_event(project_dir, "error", where=where, message=str(message)[:1000])


def read_edits(project_dir: str | None, session_id: str | None) -> list[dict]:
    """读本会话的编辑记录（edits.jsonl 按 session_id 过滤）。

    损坏行（JSON 解析失败）会被跳过，但会累计计数由调用方决定是否告警——
    静默丢弃会让"记录器写坏了"变成无痕迹故障。
    坏行计数写入模块级 _LAST_BAD_LINES 供上层读取。
    """
    global _LAST_BAD_LINES
    path = state_dir(project_dir) / "edits.jsonl"
    _LAST_BAD_LINES = 0
    if not path.exists():
        return []
    out: list[dict] = []
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except Exception:
                    _LAST_BAD_LINES += 1
                    continue
                if not isinstance(rec, dict):
                    _LAST_BAD_LINES += 1
                    continue
                if session_id and rec.get("session_id") != session_id:
                    continue
                out.append(rec)
    except Exception:
        return []
    return out


_LAST_BAD_LINES = 0


def last_bad_line_count() -> int:
    """最近一次 read_edits 跳过的坏行数（>0 说明 edits.jsonl 有损坏）。"""
    return _LAST_BAD_LINES


def emit(payload: dict) -> None:
    """把 JSON 输出到 stdout（hook 契约）。"""
    try:
        sys.stdout.write(json.dumps(payload, ensure_ascii=True))
        sys.stdout.flush()
    except Exception:
        pass


def read_stdin_json() -> dict:
    """读 stdin 的单个 JSON 对象；失败返回 {} 并把原因记入 _LAST_STDIN_ERROR（fail-open）。

    不在此处写日志：调用方（record/stop）才知道 CLAUDE_PROJECT_DIR 与上下文。
    静默吞掉解析错误会让"hook 接口/载荷变了"表现为"机制莫名不工作"。
    """
    global _LAST_STDIN_ERROR
    _LAST_STDIN_ERROR = ""
    try:
        raw = sys.stdin.buffer.read()
        if not raw.strip():
            _LAST_STDIN_ERROR = "empty stdin"
            return {}
        data = json.loads(raw.decode("utf-8", errors="replace"))
        if not isinstance(data, dict):
            _LAST_STDIN_ERROR = f"stdin JSON is {type(data).__name__}, want object"
            return {}
        return data
    except Exception as exc:
        _LAST_STDIN_ERROR = f"{type(exc).__name__}: {exc}"
        return {}


_LAST_STDIN_ERROR = ""


def last_stdin_error() -> str:
    return _LAST_STDIN_ERROR
