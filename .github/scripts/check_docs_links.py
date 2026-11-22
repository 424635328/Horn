#!/usr/bin/env python3
"""Zero-dead-link checker for README*.md and docs/**.md.

Verifies that every relative Markdown link/image target and every backtick-quoted
repository source path actually exists on disk. External http(s) links are skipped.
Anchors (#L12, #section) are stripped before resolution.

Exit codes: 0 = clean, 2 = dead links found, 4 = self-test failed.
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

DOC_GLOBS = ("README*.md", "docs/**/*.md", "CONTRIBUTING.md", "SECURITY.md")

# [label](target) and ![alt](target); target may be <wrapped>.
LINK_RE = re.compile(r"!?\[[^\]]*\]\(\s*<?([^)>\s]+)>?\s*\)")

# `path/to/File.kt` — only treat as a repo path when it has a known source suffix.
PATH_RE = re.compile(r"`([A-Za-z0-9_\-./]+\.(?:kt|kts|cpp|h|hpp|xml|yml|yaml|toml|pro|txt|md|agsl|gradle))`")

KNOWN_SUFFIXES = (".kt", ".kts", ".cpp", ".h", ".xml", ".yml", ".yaml", ".toml", ".pro", ".txt", ".md")


def iter_doc_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for pattern in DOC_GLOBS:
        files.extend(sorted(root.glob(pattern)))
    seen: set[Path] = set()
    unique: list[Path] = []
    for f in files:
        if f.is_file() and f not in seen:
            seen.add(f)
            unique.append(f)
    return unique


def strip_anchor(target: str) -> str:
    return target.split("#", 1)[0]


def is_external(target: str) -> bool:
    return target.startswith(("http://", "https://", "mailto:", "tel:"))


def check_docs(root: Path) -> list[str]:
    errors: list[str] = []
    for doc in iter_doc_files(root):
        text = doc.read_text(encoding="utf-8")
        rel = doc.relative_to(root).as_posix()
        candidates: list[str] = []
        for match in LINK_RE.finditer(text):
            candidates.append(match.group(1))
        for match in PATH_RE.finditer(text):
            candidates.append(match.group(1))
        for raw in candidates:
            if is_external(raw):
                continue
            target = strip_anchor(raw)
            if not target:
                continue
            resolved = (doc.parent / target).resolve()
            if not resolved.exists():
                resolved_root = (root / target).resolve()
                if not resolved_root.exists():
                    errors.append(f"{rel}: dead reference -> {raw}")
    return errors


def self_test() -> int:
    """Positive and negative control: the checker must flag a dead link and pass a live one."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "docs").mkdir()
        (root / "real.kt").write_text("class Real\n", encoding="utf-8")
        (root / "docs" / "good.md").write_text(
            "See [real](../real.kt) and `real.kt`.\n", encoding="utf-8"
        )
        (root / "docs" / "bad.md").write_text(
            "See [ghost](../ghost.kt) and `missing/Nope.kt`.\n", encoding="utf-8"
        )
        (root / "docs" / "good.md").write_text(
            "See [real](../real.kt) and `real.kt`.\n"
            "External [link](https://example.com/a.png) must be ignored.\n",
            encoding="utf-8",
        )
        # Negative control: good.md must produce zero errors.
        good_errors = [e for e in check_docs(root) if e.startswith("docs/good.md")]
        if good_errors:
            print("SELF-TEST FAIL: false positive on valid links:", good_errors)
            return 4
        # Positive control: bad.md must be flagged exactly twice.
        bad_errors = [e for e in check_docs(root) if e.startswith("docs/bad.md")]
        if len(bad_errors) != 2:
            print("SELF-TEST FAIL: expected 2 dead-link hits, got:", bad_errors)
            return 4
    print("self-test OK (positive + negative control)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    errors = check_docs(ROOT)
    if errors:
        print(f"Found {len(errors)} dead reference(s):")
        for error in errors:
            print("  " + error)
        return 2
    print("docs link integrity: 0 dead references")
    return 0


if __name__ == "__main__":
    sys.exit(main())
