#!/usr/bin/env python3
"""doc_sync_stop.py — Stop hook 入口（分层①实时提醒）。

运行算法严格按计划 §5.2 顺序：
  1. stop_hook_active == true → 放行（官方防循环字段）
  2. DOC_SYNC_DISABLE=1 → 放行
  3. 读本会话 edits → 路径归一化 → 去重
  4. 归属：按文件和最近 .git 祖先分仓库，加载该仓库 doc-map
  5. 判定 a→f（无代码编辑 / 有文档编辑 / 声明 / 内容键去重 / remind / block-must）
  6. 事件追加 events-<hostname>.jsonl

P0a 实证（见运维手册）：
- `additionalContext` 的续跑**不会**产生第二次 Stop（stop_hook_active 在该路径恒 false），
  故 block-must 阶段的防循环必须依赖本脚本的内容键去重 + harness 的 8 次续跑上限。
- `remind` 阶段只用 systemMessage —— 不唤醒模型，用户可见。
- 一切失败 fail-open：静默 exit 0 并写日志。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import doc_sync_lib as lib  # noqa: E402

REMIND_FOOTER = (
    "本提示仅本地层；PR 合入前 CI 仍会检查——豁免需在 PR 走记录通道。"
)


def _collect_edits(project_dir: str | None, session_id: str | None) -> tuple[list[str], int]:
    """返回 (去重后的归一化绝对路径列表, 原始记录数)。"""
    records = lib.read_edits(project_dir, session_id)
    seen: set[str] = set()
    files: list[str] = []
    for rec in records:
        raw = str(rec.get("path") or rec.get("file_path") or "").strip()
        if not raw:
            continue
        norm = lib.normalize_path(raw)
        key = norm.lower() if os.name == "nt" else norm
        if key in seen:
            continue
        seen.add(key)
        files.append(norm)
    return files, len(records)


def _group_by_repo(files: list[str]) -> dict[Path, list[str]]:
    """按每个文件最近 .git 祖先分组；返回 {repo_root: [仓库根相对 POSIX 路径]}。"""
    groups: dict[Path, list[str]] = {}
    for raw in files:
        path = Path(raw)
        repo = lib.find_repo_root(path)
        if repo is None:
            continue
        try:
            rel = path.resolve().relative_to(repo.resolve()).as_posix()
        except Exception:
            rel = lib.normalize_path(raw)
        groups.setdefault(repo, []).append(rel)
    return groups


def _relevant(rules_files: dict[str, list[str]], doc_map: dict) -> list[tuple[str, str, list[str]]]:
    """从 {rule_id: [files]} 得出 [(tier, rule_id, files)]，只保留有命中的规则。"""
    by_id = {str(r.get("id")): r for r in (doc_map.get("rules") or [])}
    out: list[tuple[str, str, list[str]]] = []
    for rid, files in rules_files.items():
        rule = by_id.get(rid)
        if not rule:
            continue
        out.append((str(rule.get("tier")), rid, files))
    # must 优先
    out.sort(key=lambda item: 0 if item[0] == "must" else 1)
    return out


def main() -> int:
    project_dir = os.environ.get("CLAUDE_PROJECT_DIR")

    # 1. 官方防循环字段
    data = lib.read_stdin_json()
    if not data:
        err = lib.last_stdin_error()
        if err and err != "empty stdin":
            lib.log_error(project_dir, "stop_stdin", err)
        return 0
    if data.get("stop_hook_active") is True:
        return 0

    # 2. 脚本级紧急开关
    if os.environ.get("DOC_SYNC_DISABLE") == "1":
        return 0

    try:
        session_id = data.get("session_id")
        declared = lib.detect_declaration(data.get("last_assistant_message"))

        # 3. 本会话编辑
        files, raw_count = _collect_edits(project_dir, session_id)
        if lib.last_bad_line_count() > 0:
            lib.log_error(
                project_dir, "read_edits",
                f"{lib.last_bad_line_count()} corrupt line(s) skipped in edits.jsonl",
            )
        if not files:
            return 0

        # 4. 按仓库归属
        groups = _group_by_repo(files)
        if not groups:
            return 0

        repo_reports: list[dict] = []
        for repo_root, rel_files in groups.items():
            try:
                doc_map = lib.load_map(repo_root)
            except lib.MapError as exc:
                lib.log_error(project_dir, "load_map", f"{repo_root}: {exc}")
                continue

            classified = lib.classify_files(repo_root, doc_map, rel_files)

            # 5.a 无代码文件编辑 → 放行
            if not classified.get("code"):
                continue

            # 5.b 有文档编辑 → 放行（粗判；正确性交 CI 精判）
            if classified.get("docs"):
                lib.log_event(
                    project_dir, "skip_docs_edited",
                    session_id=session_id, repo=str(repo_root),
                    docs=classified["docs"][:20],
                )
                continue

            relevant = _relevant(classified.get("matched_rules") or {}, doc_map)
            if not relevant:
                continue

            repo_reports.append({
                "repo": str(repo_root),
                "repo_name": str(doc_map.get("repo") or repo_root.name),
                "relevant": relevant,
                "code": classified.get("code") or [],
                "tier": "must" if any(t == "must" for t, _, _ in relevant) else "should",
                "doc_map": doc_map,
            })

        if not repo_reports:
            return 0

        all_code = sorted({f for r in repo_reports for f in r["code"]})
        key = lib.dedup_key(session_id or "", all_code, declared)

        # 5.c 声明放行
        if declared:
            lib.log_event(
                project_dir, "declared",
                session_id=session_id, key=key, files=all_code[:50],
            )
            return 0

        # 5.d 内容键去重
        if _already_reported(project_dir, session_id, key):
            return 0

        overall_tier = (
            "must" if any(r["tier"] == "must" for r in repo_reports) else "should"
        )

        hook_mode = "remind"
        for report in repo_reports:
            hook = report["doc_map"].get("hook") or {}
            if hook.get("mode") == "block-must":
                hook_mode = "block-must"
                break

        lines = _format_lines(repo_reports)

        # 6. 事件记录
        lib.log_event(
            project_dir, "report",
            session_id=session_id, key=key, tier=overall_tier,
            hook_mode=hook_mode, files=all_code[:50],
        )
        _remember(project_dir, session_id, key)

        # 5.e / 5.f
        if hook_mode == "block-must" and overall_tier == "must":
            lib.emit({
                "hookSpecificOutput": {
                    "hookEventName": "Stop",
                    "additionalContext": "\n".join(lines + ["", REMIND_FOOTER]),
                }
            })
        else:
            # remind 阶段：只提醒用户，不唤醒模型
            lib.emit({"systemMessage": "\n".join(lines + ["", REMIND_FOOTER])})
        return 0

    except Exception as exc:  # fail-open
        lib.log_error(project_dir, "stop", f"{type(exc).__name__}: {exc}")
        return 0


def _format_lines(repo_reports: list[dict]) -> list[str]:
    lines = ["[doc-sync] 本轮改动命中代码规则，建议核对文档："]
    for report in repo_reports:
        for tier, rid, hit_files in report["relevant"]:
            rule = next(
                (r for r in (report["doc_map"].get("rules") or []) if str(r.get("id")) == rid),
                {},
            )
            required = lib.required_doc_paths(rule)
            optional = lib.rule_doc_paths(rule)
            if required:
                docs = "、".join(required)
            else:
                # should 级通常只给 optional 目录 glob，转成人读提示
                dirs = sorted({p.rstrip("/*") for p in optional}) or []
                if dirs:
                    docs = "、".join(f"{d}/ 下相关文档" for d in dirs)
                else:
                    docs = "（无指定文档，建议核对对应模块 md/）"
            shown = "、".join(hit_files[:5])
            if len(hit_files) > 5:
                shown += f" 等 {len(hit_files)} 个文件"
            lines.append(f"- [{tier}] {rid}：{shown} → 建议更新：{docs}")
    return lines


def _dedup_path(project_dir: str | None) -> Path:
    return lib.state_dir(project_dir) / "last-report.json"


def _already_reported(project_dir: str | None, session_id: str | None, key: str) -> bool:
    import json
    path = _dedup_path(project_dir)
    try:
        if not path.exists():
            return False
        data = json.loads(path.read_text(encoding="utf-8"))
        return isinstance(data, dict) and data.get("session_id") == session_id and data.get("key") == key
    except Exception:
        return False


def _remember(project_dir: str | None, session_id: str | None, key: str) -> None:
    import json
    try:
        path = _dedup_path(project_dir)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"session_id": session_id, "key": key}, ensure_ascii=True),
            encoding="utf-8",
        )
    except Exception:
        pass


if __name__ == "__main__":
    sys.exit(main())
