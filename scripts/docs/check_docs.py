#!/usr/bin/env python3
"""Minimal documentation checks for the PickNear md tree."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote


REPO_ROOT = Path(__file__).resolve().parents[2]
DOCS_ROOT = REPO_ROOT / "md"
ALLOWED_STATUSES = {"draft", "current", "legacy"}
LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
IGNORED_LINK_PREFIXES = ("http://", "https://", "mailto:", "tel:")


def read_frontmatter(path: Path) -> dict[str, object] | None:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "---":
        return None

    data: dict[str, object] = {}
    active_list: str | None = None
    for line in lines[1:]:
        if line.strip() == "---":
            break
        if line.startswith("  - "):
            if active_list is None:
                continue
            values = data.setdefault(active_list, [])
            if isinstance(values, list):
                values.append(line[4:].strip().strip("\"'"))
            continue
        if ":" not in line:
            continue

        key, raw_value = line.split(":", 1)
        key = key.strip()
        value = raw_value.strip().strip("\"'")
        active_list = None
        if value:
            data[key] = [] if value == "[]" else value
        else:
            data[key] = []
            active_list = key
    return data


def as_paths(value: object) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


def markdown_links(path: Path) -> list[str]:
    return [match.strip() for match in LINK_PATTERN.findall(path.read_text(encoding="utf-8"))]


def relative_target(source: Path, raw_link: str) -> Path | None:
    link = raw_link.strip()
    if link.startswith("<") and link.endswith(">"):
        link = link[1:-1]
    link = unquote(link)
    if not link or link.startswith("#") or link.startswith(IGNORED_LINK_PREFIXES):
        return None

    target = link.split("#", 1)[0].strip()
    if not target or target.startswith(IGNORED_LINK_PREFIXES):
        return None
    resolved = (source.parent / target).resolve()
    try:
        resolved.relative_to(REPO_ROOT)
    except ValueError:
        # Workspace-only links such as ../../vm-docs are not part of the repo.
        return None
    return resolved


def module_readme(path: Path) -> Path | None:
    for parent in (path.parent, *path.parent.parents):
        if parent == DOCS_ROOT.parent:
            break
        candidate = parent / "README.md"
        if candidate.exists() and candidate.resolve() != path.resolve():
            return candidate
    return None


def links_to(readme: Path) -> set[Path]:
    targets: set[Path] = set()
    for raw_link in markdown_links(readme):
        target = relative_target(readme, raw_link)
        if target is not None:
            targets.add(target)
    return targets


def main() -> int:
    errors: list[str] = []
    current_docs: list[Path] = []
    legacy_docs: list[Path] = []
    migrated_docs = 0

    index_files = sorted(DOCS_ROOT.rglob("README.md"))
    link_checked_docs: list[Path] = []
    for path in sorted(DOCS_ROOT.rglob("*.md")):
        frontmatter = read_frontmatter(path)
        if frontmatter is None:
            continue
        migrated_docs += 1

        status = str(frontmatter.get("status", "")).strip()
        if status not in ALLOWED_STATUSES:
            errors.append(f"{path.relative_to(REPO_ROOT)}: invalid status '{status}'")
        elif status == "current":
            current_docs.append(path)
            link_checked_docs.append(path)
        elif status == "legacy":
            legacy_docs.append(path)

        for source in as_paths(frontmatter.get("source_of_truth")):
            if not (REPO_ROOT / source).exists():
                errors.append(
                    f"{path.relative_to(REPO_ROOT)}: source_of_truth not found: {source}"
                )

        for replacement in as_paths(frontmatter.get("superseded_by")):
            if not (REPO_ROOT / replacement).exists():
                errors.append(
                    f"{path.relative_to(REPO_ROOT)}: superseded_by not found: {replacement}"
                )

    for path in sorted(set(index_files + link_checked_docs)):
        for raw_link in markdown_links(path):
            target = relative_target(path, raw_link)
            if target is not None and not target.exists():
                errors.append(
                    f"{path.relative_to(REPO_ROOT)}: link target not found: {raw_link}"
                )

    for path in current_docs:
        readme = module_readme(path)
        if readme is None:
            errors.append(f"{path.relative_to(REPO_ROOT)}: no module README found")
            continue
        if path.resolve() not in links_to(readme):
            errors.append(
                f"{path.relative_to(REPO_ROOT)}: current document is not linked from "
                f"{readme.relative_to(REPO_ROOT)}"
            )

    for path in legacy_docs:
        readme = module_readme(path)
        if readme is not None and path.resolve() in links_to(readme):
            errors.append(
                f"{path.relative_to(REPO_ROOT)}: legacy document is linked from "
                f"{readme.relative_to(REPO_ROOT)}"
            )

    if errors:
        print("Documentation check failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(
        "Documentation check passed: "
        f"{len(set(index_files))} indexes, "
        f"{len(current_docs)} current docs, "
        f"{len(legacy_docs)} legacy docs, "
        f"{migrated_docs} files with frontmatter."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
