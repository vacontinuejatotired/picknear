#!/usr/bin/env python3
"""doc_sync_selftest.py — P0b 本地自测（无需 Claude 会话即可跑）。

覆盖计划 §12 P0b 验收的确定性部分：
  - doc-map validate（schema / glob 命中 / required_docs 存在且被跟踪）
  - glob 方言边界
  - Stop 判定：must 命中提醒 / 文档编辑放行 / 声明抑制 / 内容键去重 / block-must 续跑
  - 路径归一化（Windows 反斜杠）
  - fail-open（坏 JSON、缺字段、DOC_SYNC_DISABLE）

用法（仓库根）：python scripts/docs/doc_sync_selftest.py
退出码 0=全过，1=有失败。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
STATE = REPO.parent / ".claude" / "doc-sync"

FAILURES: list[str] = []
CHECKS = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global CHECKS
    CHECKS += 1
    if ok:
        print(f"  ok   {name}")
    else:
        print(f"  FAIL {name}" + (f"  -- {detail}" if detail else ""))
        FAILURES.append(name)


def write_edits(records: list[dict]) -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    (STATE / "edits.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records),
        encoding="utf-8",
        newline="\n",
    )


def run_stop(payload: dict, env_extra: dict | None = None) -> dict:
    env = dict(os.environ)
    env["CLAUDE_PROJECT_DIR"] = str(REPO.parent)
    if env_extra:
        env.update(env_extra)
    proc = subprocess.run(
        ["sh", str(HERE / "doc_sync_hook.sh"), "doc_sync_stop.py"],
        input=json.dumps(payload).encode("utf-8"),
        capture_output=True,
        cwd=str(REPO),
        env=env,
        timeout=60,
    )
    out = (proc.stdout or b"").decode("utf-8", errors="replace").strip()
    if not out:
        return {}
    try:
        return json.loads(out)
    except Exception:
        return {"__raw__": out}


def stop_payload(session: str, msg: str = "改完了", active: bool = False) -> dict:
    return {
        "session_id": session,
        "hook_event_name": "Stop",
        "cwd": str(REPO.parent),
        "stop_hook_active": active,
        "last_assistant_message": msg,
    }


sys.path.insert(0, str(HERE))
import doc_sync_lib as lib  # noqa: E402


def main() -> int:
    print("== 1. doc-map validate ==")
    doc_map = lib.load_map(REPO)
    check("doc-map 加载", doc_map.get("repo") == "picknear")
    tracked, err = lib.git_ls_files(REPO)
    check("git ls-files", err is None and len(tracked) > 100, f"err={err} n={len(tracked)}")

    ids = [str(r["id"]) for r in doc_map["rules"]]
    check("rule id 唯一", len(ids) == len(set(ids)))
    for rule in doc_map["rules"]:
        rid = rule["id"]
        hits = lib.expand_globs(REPO, [str(g) for g in rule["code"]], tracked)
        check(f"rule {rid} code glob 命中≥1", len(hits) > 0, f"hits={len(hits)}")
        for path in lib.required_doc_paths(rule):
            check(f"rule {rid} required_doc 存在", (REPO / path).exists(), path)
            check(f"rule {rid} required_doc 被跟踪", path in tracked, path)

    print("== 2. glob 方言 ==")
    cases = [
        ("a/b/C.java", "a/b/**", True),
        ("a/b/x/y/C.java", "a/b/**", True),
        ("a/bX/C.java", "a/b/**", False),
        ("r/application.yaml", "r/application*.yaml", True),
        ("r/application-dev.yaml", "r/application*.yaml", True),
        ("x/target/y.py", "**/target/**", True),
        ("deep/n/t.py", "**/t.py", True),
        ("t.py", "**/t.py", True),
        ("only.py", "**.py", True),
        ("md/中文.md", "**/*.md", True),
    ]
    for path, pat, want in cases:
        check(f"glob {pat} ~ {path}", lib.glob_match(path, pat) == want)

    print("== 3. 路径归一化 ==")
    check("反斜杠归一",
          lib.normalize_path(r"E:\heima\picknear\a\b.py") == "E:/heima/picknear/a/b.py")
    check("相对路径归一", lib.normalize_path("./a/b.py") == "a/b.py")

    print("== 4. 声明检测 ==")
    check("末行声明生效", lib.detect_declaration("说明\n\nDocs-Impact: none — 纯重构"))
    check("破折号变体", lib.detect_declaration("Docs-Impact: none – 理由"))
    check("裸声明不生效", not lib.detect_declaration("Docs-Impact: none"))
    check("声明在中间不生效",
          not lib.detect_declaration("Docs-Impact: none — x\n\n后面还有别的话"))
    check("空值", not lib.detect_declaration(None))

    print("== 5. Stop 判定 ==")
    MUST_FILE = r"picknear\src\main\java\com\hmdp\agent\controller\ChatController.java"
    DOC_FILE = r"md\agent\SSE后端实现规范.md"
    SHOULD_FILE = r"picknear\src\main\java\com\hmdp\agent\service\ChatService.java"

    # 5.1 must 命中、无文档 → systemMessage（remind）
    write_edits([{"session_id": "s1", "tool_name": "Edit", "file_path": MUST_FILE}])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s1"))
    check("must 命中 → systemMessage", "systemMessage" in out, str(out)[:200])
    check("remind 不续跑（无 additionalContext）", "hookSpecificOutput" not in out)

    # 5.2 内容键去重 → 静默
    out2 = run_stop(stop_payload("s1"))
    check("同状态重复 → 静默去重", out2 == {}, str(out2)[:200])

    # 5.3 改动了文档 → 放行
    write_edits([
        {"session_id": "s2", "tool_name": "Edit", "file_path": MUST_FILE},
        {"session_id": "s2", "tool_name": "Edit", "file_path": DOC_FILE},
    ])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s2"))
    check("有文档编辑 → 放行", out == {}, str(out)[:200])

    # 5.4 声明 → 放行
    write_edits([{"session_id": "s3", "tool_name": "Edit", "file_path": MUST_FILE}])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s3", msg="改完了\n\nDocs-Impact: none — 仅内部重命名"))
    check("末行声明 → 放行", out == {}, str(out)[:200])

    # 5.5 会话隔离
    write_edits([{"session_id": "other", "tool_name": "Edit", "file_path": MUST_FILE}])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s-none"))
    check("其它会话的记录不影响本会话", out == {}, str(out)[:200])

    # 5.6 only-should 命中 → remind 仍然提醒
    write_edits([{"session_id": "s4", "tool_name": "Edit", "file_path": SHOULD_FILE}])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s4"))
    check("should 命中 → 有提醒", "systemMessage" in out, str(out)[:200])

    # 5.7 stop_hook_active → 放行
    write_edits([{"session_id": "s5", "tool_name": "Edit", "file_path": MUST_FILE}])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s5", active=True))
    check("stop_hook_active=true → 放行", out == {}, str(out)[:200])

    # 5.8 DOC_SYNC_DISABLE → 放行
    write_edits([{"session_id": "s6", "tool_name": "Edit", "file_path": MUST_FILE}])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s6"), env_extra={"DOC_SYNC_DISABLE": "1"})
    check("DOC_SYNC_DISABLE=1 → 放行", out == {}, str(out)[:200])

    # 5.9 unmanaged → 放行
    write_edits([{
        "session_id": "s7", "tool_name": "Edit",
        "file_path": r"picknear\src\main\java\com\hmdp\entity\Voucher.java",
    }])
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s7"))
    check("unmanaged → 放行", out == {}, str(out)[:200])

    print("== 6. block-must 模式 ==")
    original = (REPO / "scripts" / "docs" / "doc-map.json").read_text(encoding="utf-8")
    try:
        mutated = json.loads(original)
        mutated["hook"]["mode"] = "block-must"
        (REPO / "scripts" / "docs" / "doc-map.json").write_text(
            json.dumps(mutated, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8", newline="\n",
        )
        write_edits([{"session_id": "s8", "tool_name": "Edit", "file_path": MUST_FILE}])
        (STATE / "last-report.json").unlink(missing_ok=True)
        out = run_stop(stop_payload("s8"))
        specific = out.get("hookSpecificOutput") or {}
        check("block-must: must → additionalContext", bool(specific.get("additionalContext")), str(out)[:200])
        check("block-must: hookEventName=Stop", specific.get("hookEventName") == "Stop")

        # should-only 在 block-must 下仍是 systemMessage
        write_edits([{"session_id": "s9", "tool_name": "Edit", "file_path": SHOULD_FILE}])
        (STATE / "last-report.json").unlink(missing_ok=True)
        out = run_stop(stop_payload("s9"))
        check("block-must: should → systemMessage", "systemMessage" in out, str(out)[:200])
    finally:
        (REPO / "scripts" / "docs" / "doc-map.json").write_text(
            original, encoding="utf-8", newline="\n"
        )

    print("== 7. 豁免理由解析 ==")
    import argparse as _ap

    def exemption(body: str, labels: str = "docs-exempt", labeled: str = "", sync: str = ""):
        env_backup = {k: os.environ.get(k) for k in
                      ("DOC_SYNC_LABELS", "DOC_SYNC_PR_BODY",
                       "DOC_SYNC_LABELED_AT", "DOC_SYNC_LAST_SYNC_AT")}
        try:
            os.environ["DOC_SYNC_LABELS"] = labels
            os.environ["DOC_SYNC_PR_BODY"] = body
            os.environ["DOC_SYNC_LABELED_AT"] = labeled
            os.environ["DOC_SYNC_LAST_SYNC_AT"] = sync
            sys.path.insert(0, str(HERE))
            import doc_sync_check as chk
            return chk._exemption(_ap.Namespace(labels=None, pr_body=None))
        finally:
            for k, v in env_backup.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v

    ok, reason = exemption("理由：纯重构")
    check("模板式理由成立", ok and reason == "纯重构", f"{ok} {reason}")
    ok, _ = exemption("- [x] 无需更新\n  理由：无对外变化")
    check("带列表前缀的理由成立", ok)
    ok, _ = exemption("没有理由字段")
    check("句子含'理由'二字但无声明 → 不豁免", not ok)
    ok, _ = exemption("理由：")
    check("空理由 → 不豁免", not ok)
    ok, _ = exemption("理由：x", labels="other-label")
    check("无 docs-exempt 标签 → 不豁免", not ok)
    ok, _ = exemption("理由：x", labeled="2026-10-11T08:00:00Z", sync="2026-10-11T09:00:00Z")
    check("僵尸豁免（标签早于同步）→ 不豁免", not ok)
    ok, _ = exemption("理由：x", labeled="2026-10-11T10:00:00Z", sync="2026-10-11T09:00:00Z")
    check("标签晚于同步 → 豁免成立", ok)

    print("== 8. fail-open ==")
    (STATE / "edits.jsonl").write_text("{这不是合法 JSON\n", encoding="utf-8", newline="\n")
    (STATE / "last-report.json").unlink(missing_ok=True)
    out = run_stop(stop_payload("s10"))
    check("坏 edits.jsonl → 静默 fail-open", out == {}, str(out)[:200])

    write_edits([])
    out = run_stop({})
    check("空 stdin → 静默", out == {}, str(out)[:200])

    print()
    print(f"共 {CHECKS} 项检查，失败 {len(FAILURES)} 项")
    if FAILURES:
        for name in FAILURES:
            print(f"  - {name}")
        return 1
    print("全部通过 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
