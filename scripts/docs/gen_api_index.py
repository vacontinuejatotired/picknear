#!/usr/bin/env python3
"""gen_api_index.py — 从 controller 注解派生 API 端点清单，写入文档托管区块。

用途（分层② 的"内容一致性检查"样板，见 md/规范/文档同步规范.md §7.4）：
    扫描 @RestController/@Controller 类上的 @RequestMapping（类级前缀）
    与方法级 @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping，
    生成「方法 + 完整路径 + 所在类」清单，覆盖写入 md/ops/API接口文档.md 中：

        <!-- doc-sync:api-index begin -->
        ...（本脚本生成，勿手改）...
        <!-- doc-sync:api-index end -->

    块外内容由人正常编辑，本脚本绝不触碰。

用法（仓库根）：
    python scripts/docs/gen_api_index.py            # 写入文档
    python scripts/docs/gen_api_index.py --check    # 只校验是否一致（CI 用；不一致 exit 1）
    python scripts/docs/gen_api_index.py --stdout   # 打印生成内容，不写文件

CI 用法：跑生成器后 `git diff --exit-code md/ops/API接口文档.md`
（或直接用 --check），端点与文档区块不同步即红。

设计取舍：
- 不引入 Java parser（正则 + 括号配平足够，且注解全是字面量——已核实无拼接形式）；
- 只认字面量路径；遇到 `@RequestMapping(CONSTANT)` 这类非字面量会记入 skipped 并在输出中提示，
  不静默丢弃（防"少列了端点却没人知道"）。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DOC = REPO / "md" / "ops" / "API接口文档.md"
BEGIN = "<!-- doc-sync:api-index begin -->"
END = "<!-- doc-sync:api-index end -->"

CONTROLLER_RE = re.compile(r"@(RestController|Controller)\b")
CLASS_REQ_RE = re.compile(r"@RequestMapping\s*\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
MAPPING_RE = re.compile(
    r"@(Get|Post|Put|Delete|Patch)Mapping\s*"
    r"(?:\(\s*(?:value\s*=\s*)?(?:\"([^\"]*)\")?[^)]*\))?"
)
# 非字面量形式的类级映射（如 @RequestMapping(API_PREFIX)）——记入 skipped
NONLITERAL_RE = re.compile(r"@(?:Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?!\s*(?:value\s*=\s*)?\")")

HTTP_VERB = {
    "Get": "GET",
    "Post": "POST",
    "Put": "PUT",
    "Delete": "DELETE",
    "Patch": "PATCH",
}


def list_controllers() -> list[Path]:
    root = REPO / "picknear" / "src" / "main" / "java"
    if not root.exists():
        return []
    out = [
        p for p in root.rglob("*.java")
        if p.name.endswith("Controller.java")
    ]
    return sorted(out)


def join_path(prefix: str, sub: str) -> str:
    """拼接类级前缀与方法级子路径，规整斜杠。"""
    prefix = (prefix or "").strip()
    sub = (sub or "").strip()
    if not prefix and not sub:
        return "/"
    if not prefix:
        return "/" + sub.lstrip("/") if sub else "/"
    if not sub:
        return "/" + prefix.lstrip("/")
    return "/" + "/".join([prefix.strip("/"), sub.strip("/")])


def parse_controller(path: Path) -> tuple[list[dict], list[str]]:
    """返回 ([{verb, path, method}], [skipped 说明])。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()

    # 类声明位置：第一个 @RestController/@Controller
    class_line_idx = None
    for i, line in enumerate(lines):
        if CONTROLLER_RE.search(line):
            class_line_idx = i
            break
    if class_line_idx is None:
        return [], []

    # 类级 @RequestMapping：取类声明附近（前后 5 行）
    prefix = ""
    lo = max(0, class_line_idx - 3)
    hi = min(len(lines), class_line_idx + 6)
    for line in lines[lo:hi]:
        m = CLASS_REQ_RE.search(line)
        if m:
            prefix = m.group(1)
            break

    entries: list[dict] = []
    skipped: list[str] = []
    class_short = path.stem

    for i, line in enumerate(lines):
        if NONLITERAL_RE.search(line) and not MAPPING_RE.search(line):
            skipped.append(f"{class_short}:{i + 1} 非字面量映射: {line.strip()[:80]}")
            continue
        for m in MAPPING_RE.finditer(line):
            verb_key, sub = m.group(1), m.group(2) or ""
            entries.append({
                "verb": HTTP_VERB[verb_key],
                "path": join_path(prefix, sub),
                "method": class_short,
            })

    # 同一 controller 内按路径排序，便于阅读
    entries.sort(key=lambda e: (e["path"], e["verb"], e["method"]))
    return entries, skipped


def collect() -> tuple[list[dict], list[str], list[Path], list[str]]:
    controllers = list_controllers()
    all_entries: list[dict] = []
    all_skipped: list[str] = []
    empty_controllers: list[str] = []
    for path in controllers:
        entries, skipped = parse_controller(path)
        if not entries:
            empty_controllers.append(path.stem)
        all_entries.extend(entries)
        all_skipped.extend(skipped)
    all_entries.sort(key=lambda e: (e["path"], e["verb"], e["method"]))
    return all_entries, all_skipped, controllers, empty_controllers


def render(
    entries: list[dict],
    skipped: list[str],
    controllers: list[Path],
    empty_controllers: list[str],
) -> str:
    lines = [
        BEGIN,
        "",
        "### 自动生成的端点清单",
        "",
        "> 本节由 `scripts/docs/gen_api_index.py` 从 controller 注解派生，**请勿手工编辑**。",
        f"> 覆盖 {len(controllers)} 个 Controller，共 {len(entries)} 个端点。",
        "> 修改接口后运行 `python scripts/docs/gen_api_index.py` 重新生成。",
        "",
    ]
    if empty_controllers:
        lines += [
            f"> 以下 Controller 无方法级映射（空实现或未完成），故无端点："
            f"{'、'.join('`' + c + '`' for c in empty_controllers)}",
            "",
        ]
    if skipped:
        lines += ["> ⚠️ 以下映射非字面量路径，未能解析（请手工登记）：", ""]
        lines += [f"> - {s}" for s in skipped]
        lines += [""]

    lines += ["| 方法 | 路径 | 所在类 |", "|---|---|---|"]
    for e in entries:
        lines.append(f"| `{e['verb']}` | `{e['path']}` | `{e['method']}` |")
    lines += ["", END]
    return "\n".join(lines)


def splice(existing: str, block: str) -> str:
    """把 block 覆盖进现有文档的托管区块；没有区块则追加到末尾。"""
    if BEGIN in existing and END in existing:
        pre = existing.split(BEGIN, 1)[0]
        post = existing.split(END, 1)[1]
        return pre + block + post
    return existing.rstrip("\n") + "\n\n" + block + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="generate api index block")
    ap.add_argument("--check", action="store_true", help="只校验，不一致 exit 1")
    ap.add_argument("--stdout", action="store_true", help="打印生成内容，不写文件")
    args = ap.parse_args()

    entries, skipped, controllers, empty_controllers = collect()
    if not controllers:
        print("FAIL: 未找到任何 Controller（路径或仓库结构变化？）")
        return 1

    block = render(entries, skipped, controllers, empty_controllers)

    if args.stdout:
        print(block)
        return 0

    if not DOC.exists():
        print(f"FAIL: 目标文档不存在: {DOC}")
        return 1

    existing = DOC.read_text(encoding="utf-8")
    updated = splice(existing, block)

    if args.check:
        if existing.strip() == updated.strip():
            print(f"api-index 一致（{len(entries)} 端点 / {len(controllers)} Controller）")
            return 0
        print("FAIL: md/ops/API接口文档.md 的端点清单与代码不同步。")
        print("      运行 python scripts/docs/gen_api_index.py 重新生成并提交。")
        return 1

    if updated != existing:
        DOC.write_text(updated, encoding="utf-8", newline="\n")
        print(f"已更新 {DOC.relative_to(REPO)}：{len(entries)} 端点 / {len(controllers)} Controller")
    else:
        print(f"无需变更（{len(entries)} 端点 / {len(controllers)} Controller）")
    if skipped:
        print(f"注意：{len(skipped)} 处非字面量映射未解析（已在区块内标注）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
