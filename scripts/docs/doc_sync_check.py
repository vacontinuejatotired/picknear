#!/usr/bin/env python3
"""doc_sync_check.py — CI 守门与 doc-map 健康检查（分层②）。

三种模式：
  --validate                      doc-map 健康检查（§4.2 的 1–7 条），CI 每次必跑
  --gate --base X --head Y [...]  PR 判定（§6.1），observe 只报告 / enforce 才 exit 1
  --report                        内容时效 + 覆盖率报告（§7.1、§10），不改变退出码

退出码约定：
  --validate 失败 → 恒 exit 1（map 健康是硬底线，与 gates.mode 无关）
  --gate 有违规且无豁免 且 mode==enforce → exit 1；否则 exit 0
  --report 恒 exit 0

PR 上下文经环境变量传入（避免命令行注入与转义问题）：
  DOC_SYNC_BASE / DOC_SYNC_HEAD / DOC_SYNC_PR_BODY / DOC_SYNC_LABELS /
  DOC_SYNC_LABELED_AT / DOC_SYNC_LAST_SYNC_AT / DOC_SYNC_MODE
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import doc_sync_lib as lib  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
ALLOWED_STATUSES = {"draft", "current", "legacy"}


# --------------------------------------------------------------------------
# validate
# --------------------------------------------------------------------------

def cmd_validate(args: argparse.Namespace) -> int:
    failures: list[str] = []
    warnings: list[str] = []
    infos: list[str] = []

    try:
        doc_map = lib.load_map(REPO)          # 检查 1（schema）
    except lib.MapError as exc:
        print(f"FAIL: doc-map schema: {exc}")
        return 1

    # 检查 7：repo 字段须与本仓库一致
    expected_repo = REPO.name
    if str(doc_map.get("repo")) != expected_repo:
        failures.append(
            f"doc-map.repo={doc_map.get('repo')!r} 与本仓库目录名 {expected_repo!r} 不一致"
        )

    tracked, err = lib.git_ls_files(REPO)
    if err:
        print(f"FAIL: git ls-files 不可用（未能判定，不作为结论）：{err}")
        return 1
    tracked_set = set(tracked)

    rules = doc_map.get("rules") or []
    exclude_globs = lib._glob_list(doc_map.get("exclude"))
    unmanaged_globs = lib._glob_list(doc_map.get("unmanaged"))

    # 检查 2：每条 rule 的 glob 至少命中 1 个被跟踪文件
    for rule in rules:
        rid = rule.get("id")
        for key in ("code", "required_docs", "optional_docs"):
            if key != "code":
                continue  # required_docs/optional_docs 是文档路径而非 glob，见检查 3
            hits = lib.expand_globs(REPO, lib._glob_list(rule.get(key)), tracked)
            if not hits:
                failures.append(f"rule {rid}: {key} glob 未命中任何被跟踪文件（规则腐烂）")

        # 检查 3：required_docs 存在且被跟踪
        for path in lib.required_doc_paths(rule):
            if not (REPO / path).exists():
                failures.append(f"rule {rid}: required_doc 不存在: {path}")
            elif path not in tracked_set:
                failures.append(f"rule {rid}: required_doc 未被 git 跟踪: {path}")

    # 检查 4：时效（报告级）
    stale = _stale_rules(rules)
    for rid, detail in stale:
        warnings.append(f"rule {rid}: 文档末次提交早于代码 → 待核对（{detail}）")

    # 检查 5：覆盖率
    coverage, denom, numer = _coverage(doc_map, tracked)
    threshold = doc_map.get("coverage_threshold_pct")
    if isinstance(threshold, (int, float)) and coverage < threshold:
        warnings.append(
            f"覆盖率 {coverage:.1f}% < 阈值 {threshold}%"
            f"（{numer}/{denom}，分母已排除 exclude 与 unmanaged）"
        )

    # 检查 6：孤儿文档（INFO）
    orphans = _orphan_docs(doc_map, tracked)
    for path in orphans:
        infos.append(f"孤儿文档（current 但不属任何规则文档集）: {path}")

    for line in failures:
        print(f"FAIL: {line}")
    for line in warnings:
        print(f"WARN: {line}")
    for line in infos:
        print(f"INFO: {line}")

    print()
    print(f"覆盖率: {coverage:.1f}%（{numer}/{denom}）")
    print(f"规则数: {len(rules)}；孤儿文档: {len(orphans)}；待核对规则: {len(stale)}")

    if failures:
        print(f"\nvalidate 失败：{len(failures)} 项 FAIL")
        return 1
    print("\nvalidate 通过")
    return 0


def _stale_rules(rules: list[dict]) -> list[tuple[str, str]]:
    """检查 4：rule 的 docs 末次提交早于 code 末次提交 → 待核对。"""
    out: list[tuple[str, str]] = []
    for rule in rules:
        rid = rule.get("id")
        code_ts = 0
        for glob in lib._glob_list(rule.get("code")):
            ts = lib.git_last_commit_ts(REPO, glob)
            if ts:
                code_ts = max(code_ts, ts)
        if not code_ts:
            continue
        doc_paths = lib.rule_doc_paths(rule)
        if not doc_paths:
            continue
        newest_doc = 0
        for path in doc_paths:
            ts = lib.git_last_commit_ts(REPO, path)
            if ts:
                newest_doc = max(newest_doc, ts)
        if newest_doc and newest_doc < code_ts:
            import time as _t
            out.append((
                rid,
                f"docs {_t.strftime('%Y-%m-%d', _t.localtime(newest_doc))} "
                f"< code {_t.strftime('%Y-%m-%d', _t.localtime(code_ts))}",
            ))
    return out


def _coverage(doc_map: dict, tracked: list[str]) -> tuple[float, int, int]:
    """检查 5：覆盖率 = 被规则覆盖的代码文件 ÷（ls-files − exclude − unmanaged）。"""
    exclude_globs = lib._glob_list(doc_map.get("exclude"))
    unmanaged_globs = lib._glob_list(doc_map.get("unmanaged"))
    all_code_globs: list[str] = []
    for rule in doc_map.get("rules") or []:
        all_code_globs.extend(lib._glob_list(rule.get("code")))

    denom = 0
    numer = 0
    for path in tracked:
        if path.lower().endswith(".md"):
            continue
        if lib.any_glob_match(path, exclude_globs):
            continue
        if lib.any_glob_match(path, unmanaged_globs):
            continue
        denom += 1
        if lib.any_glob_match(path, all_code_globs):
            numer += 1
    if denom == 0:
        return 0.0, 0, 0
    return 100.0 * numer / denom, denom, numer


def _orphan_docs(doc_map: dict, tracked: list[str]) -> list[str]:
    """检查 6：current 文档不属任何规则文档集（README/豁免除外）。"""
    claimed: set[str] = set()
    for rule in doc_map.get("rules") or []:
        claimed.update(lib.rule_doc_paths(rule))

    exempt = lib._glob_list(doc_map.get("orphan_exempt"))
    orphans: list[str] = []
    for path in tracked:
        if not path.endswith(".md") or not path.startswith("md/"):
            continue
        if path.endswith("/README.md") or path == "md/README.md":
            continue
        if path in claimed:
            continue
        if lib.any_glob_match(path, exempt):
            continue
        full = REPO / path
        try:
            text = full.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        if not text.startswith("---"):
            continue
        head = text.split("---", 2)
        if len(head) < 2:
            continue
        if "status: current" in head[1]:
            orphans.append(path)
    return orphans


# --------------------------------------------------------------------------
# gate
# --------------------------------------------------------------------------

def cmd_gate(args: argparse.Namespace) -> int:
    try:
        doc_map = lib.load_map(REPO)
    except lib.MapError as exc:
        print(f"FAIL: doc-map schema: {exc}")
        return 1

    # 模式优先级：CLI --mode > 环境变量 DOC_SYNC_MODE > doc-map gates.mode
    mode = (
        args.mode
        or os.environ.get("DOC_SYNC_MODE")
        or str((doc_map.get("gates") or {}).get("mode") or "observe")
    )
    if mode not in lib.VALID_MODES:
        print(f"FAIL: 未知的 gate mode: {mode!r}（允许 {sorted(lib.VALID_MODES)}）")
        return 1
    base = args.base or os.environ.get("DOC_SYNC_BASE")
    head = args.head or os.environ.get("DOC_SYNC_HEAD")

    if not base or not head:
        print("FAIL: --gate 需要 --base/--head（或 DOC_SYNC_BASE/DOC_SYNC_HEAD）")
        return 1

    changed = _pr_changed_files(base, head)
    if changed is None:
        print("FAIL: git diff 不可用（未能判定）")
        return 1

    docs_changed = [f for f in changed if f.lower().endswith(".md")]
    exclude_globs = lib._glob_list(doc_map.get("exclude"))
    code_changed = [
        f for f in changed
        if not f.lower().endswith(".md") and not lib.any_glob_match(f, exclude_globs)
    ]

    violations: list[dict] = []
    for rule in doc_map.get("rules") or []:
        if rule.get("tier") != "must":
            continue
        hit = lib.expand_globs(REPO, lib._glob_list(rule.get("code")), code_changed)
        if not hit:
            continue
        required = lib.required_doc_paths(rule)
        if any(p in docs_changed for p in required):
            continue
        if lib.rule_doc_paths(rule) and any(p in docs_changed for p in lib.rule_doc_paths(rule)):
            # 动了该规则的其它文档（如 optional）→ 视为已决策，交由评审
            continue
        violations.append({"rule": rule.get("id"), "files": hit, "required": required})

    exempt, exempt_reason = _exemption(args)
    label = "observe" if mode == "observe" else "enforce"

    print(_gate_summary(mode, base, head, len(changed), docs_changed, code_changed,
                        violations, exempt, exempt_reason))

    if violations and not exempt and label == "enforce":
        return 1
    return 0


def _pr_changed_files(base: str, head: str) -> list[str] | None:
    code, out, err = lib._run_git(REPO, ["diff", "--name-only", f"{base}...{head}"])
    if code != 0:
        code2, out2, _ = lib._run_git(REPO, ["diff", "--name-only", base, head])
        if code2 != 0:
            return None
        out = out2
    return [lib.normalize_path(line) for line in (out or "").splitlines() if line.strip()]


def _exemption(args: argparse.Namespace) -> tuple[bool, str]:
    """豁免成立需三条同时满足（§6.1）：标签 + 标签时间晚于最近一次 synchronize + 理由非空。"""
    labels = (args.labels or os.environ.get("DOC_SYNC_LABELS") or "")
    body = args.pr_body or os.environ.get("DOC_SYNC_PR_BODY") or ""
    labeled_at = os.environ.get("DOC_SYNC_LABELED_AT") or ""
    last_sync = os.environ.get("DOC_SYNC_LAST_SYNC_AT") or ""

    label_list = [s.strip() for s in labels.split(",") if s.strip()]
    if "docs-exempt" not in label_list:
        return False, ""

    if labeled_at and last_sync and labeled_at < last_sync:
        return False, "标签早于最近一次 synchronize（僵尸豁免，需重新打标签）"

    reason = ""
    for line in body.splitlines():
        stripped = line.strip()
        # 只认"以 理由 开头"的声明行（PR 模板形式 `理由：<文本>`），
        # 不能对整行做子串匹配——否则"没有理由字段"这类句子会被误判成有理由。
        m = re.match(r"^[-*\s]*理由\s*[:：]\s*(.+)$", stripped)
        if m:
            candidate = m.group(1).strip()
            if candidate and not candidate.startswith("<"):
                reason = candidate
                break
    if not reason:
        return False, "标签存在但 PR 描述未填写理由"
    return True, reason


def _gate_summary(mode, base, head, n_changed, docs, code, violations, exempt, reason) -> str:
    lines = [
        f"## doc-sync gate（mode={mode}）",
        "",
        f"- base...head: `{base}...{head}`",
        f"- 变更文件: {n_changed}（文档 {len(docs)} / 代码 {len(code)}）",
        "",
    ]
    if not violations:
        lines.append("✅ 无 must 违规。")
    else:
        lines.append("| 规则 | 命中文件 | 要求文档 | 结果 | 豁免理由 |")
        lines.append("|---|---|---|---|---|")
        for v in violations:
            files = "<br>".join(v["files"][:5])
            if len(v["files"]) > 5:
                files += f"<br>… 共 {len(v['files'])} 个"
            req = "<br>".join(v["required"]) or "—"
            if exempt:
                res = "豁免"
            elif mode == "enforce":
                res = "**违规**"
            else:
                res = "违规（observe 期不阻断）"
            lines.append(f"| {v['rule']} | {files} | {req} | {res} | {reason or '—'} |")
    lines.append("")
    lines.append("> 本层保证：must 命中时至少动过一个 required_doc 或存在记录豁免。")
    lines.append("> 不保证：动的对不对、够不够——由 PR 评审与审计层承担。")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# report
# --------------------------------------------------------------------------

def cmd_report(args: argparse.Namespace) -> int:
    try:
        doc_map = lib.load_map(REPO)
    except lib.MapError as exc:
        print(f"FAIL: doc-map schema: {exc}")
        return 1

    tracked, err = lib.git_ls_files(REPO)
    if err:
        print(f"WARN: git ls-files 不可用，报告降级：{err}")
        tracked = []

    coverage, denom, numer = _coverage(doc_map, tracked)
    stale = _stale_rules(doc_map.get("rules") or [])
    orphans = _orphan_docs(doc_map, tracked)

    print("## doc-sync 内容时效报告")
    print()
    print(f"- 覆盖率: {coverage:.1f}%（{numer}/{denom}）")
    print(f"- 待核对规则（文档末次提交早于代码）: {len(stale)}")
    for rid, detail in stale:
        print(f"  - {rid}: {detail}")
    print(f"- 孤儿文档: {len(orphans)}")
    for path in orphans:
        print(f"  - {path}")
    return 0


# --------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="doc-sync check")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--validate", action="store_true")
    group.add_argument("--gate", action="store_true")
    group.add_argument("--report", action="store_true")
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--mode", choices=["observe", "enforce"])
    parser.add_argument("--pr-body")
    parser.add_argument("--labels")
    args = parser.parse_args()

    if args.validate:
        return cmd_validate(args)
    if args.gate:
        return cmd_gate(args)
    return cmd_report(args)


if __name__ == "__main__":
    sys.exit(main())
