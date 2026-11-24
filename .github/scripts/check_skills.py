#!/usr/bin/env python3
"""Integrity, sanitization and structure gate for the vendored agent skills.

`.agents/skills/` is a read-only snapshot of the skills this project actually used
(see `.agents/skills/README.md`). The path is not decoration: the harness scans
`<projectRoot>/.agents/skills` as a project skill root, so a checkout makes these
skills loadable without any per-machine install step. This checker enforces four
things:

  1. structure   — every skill directory has a SKILL.md whose YAML frontmatter
                   declares a name matching the directory and a description;
  2. sanitization — no host paths, credentials, tokens or private keys leaked into
                   the snapshot;
  3. links        — every relative Markdown link inside a skill resolves;
  4. manifest     — `.agents/skills/manifest.sha256` matches every vendored byte,
                   and no file exists outside the manifest.

Exit codes: 0 = clean, 2 = structure/sanitization/link failure, 3 = manifest
mismatch, 4 = self-test failure (the checker itself is broken).
"""

from __future__ import annotations

import hashlib
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKILLS_ROOT = ".agents/skills"
MANIFEST_NAME = "manifest.sha256"

SCAN_SUFFIXES = {".md", ".mjs", ".json", ".yaml", ".yml", ".txt"}

FORBIDDEN = (
    (re.compile(r"[A-Za-z]:\\+Users\\+", re.IGNORECASE), "forbidden windows user path"),
    (re.compile(r"[A-Za-z]:\\+GitHub\\+", re.IGNORECASE), "forbidden machine work path"),
    (re.compile(r"\bUSERPROFILE\b"), "forbidden host environment variable"),
    # Repo-relative `.agents/skills/...` is the documented layout and must pass; only
    # host-absolute forms (home paths, drive letters, leading slash) are leaks. The lookbehind
    # also rejects a preceding `/` so `../../.agents/skills/...` stays a valid relative link.
    (
        re.compile(
            r"\$HOME[/\\]\.agents"
            r"|(?<![.\w/-])(?:[A-Za-z]:[\\/]|/)[^\s\"'`)]*\.agents[\\/]skills",
            re.IGNORECASE,
        ),
        "forbidden host skill path",
    ),
    (re.compile(r"ghp_[A-Za-z0-9]{10,}"), "forbidden github token"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{10,}"), "forbidden github pat"),
    (re.compile(r"\bsk-[A-Za-z0-9]{16,}"), "forbidden api key"),
    (re.compile(r"BEGIN [A-Z ]*PRIVATE KEY"), "forbidden private key"),
    (re.compile(r"(?i)\bpassword\s*[=:]\s*\S"), "forbidden password literal"),
    (re.compile(r"(?i)\bcookie\s*[=:]\s*\S"), "forbidden cookie literal"),
)

LINK_RE = re.compile(r"!?\[[^\]]*\]\(\s*<?([^)>\s]+)>?\s*\)")


def skill_dirs(root: Path) -> list[Path]:
    base = root / SKILLS_ROOT
    if not base.is_dir():
        return []
    return sorted(path for path in base.iterdir() if path.is_dir())


def check_structure(root: Path) -> list[str]:
    errors: list[str] = []
    for skill in skill_dirs(root):
        skill_md = skill / "SKILL.md"
        if not skill_md.is_file():
            errors.append(f"{skill.name}: missing SKILL.md")
            continue
        text = skill_md.read_text(encoding="utf-8")
        if not text.startswith("---\n"):
            errors.append(f"{skill.name}: SKILL.md must start with YAML frontmatter")
            continue
        parts = text.split("---", 2)
        front = parts[1] if len(parts) > 2 else ""
        if "description:" not in front:
            errors.append(f"{skill.name}: frontmatter missing description")
        declared = re.search(r"^name:[ \t]*\"?([A-Za-z0-9._-]+)\"?[ \t]*$", front, re.MULTILINE)
        if declared is None:
            errors.append(f"{skill.name}: frontmatter missing name")
        elif declared.group(1) != skill.name:
            errors.append(
                f"{skill.name}: frontmatter name '{declared.group(1)}' does not match directory"
            )
        if len(text.strip()) < 200:
            errors.append(f"{skill.name}: SKILL.md is suspiciously short")
    return errors


def check_sanitization(root: Path) -> list[str]:
    errors: list[str] = []
    for skill in skill_dirs(root):
        for path in sorted(skill.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in SCAN_SUFFIXES:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for pattern, label in FORBIDDEN:
                for match in pattern.finditer(text):
                    line = text.count("\n", 0, match.start()) + 1
                    relative = path.relative_to(root).as_posix()
                    errors.append(f"{relative}:{line}: {label}")
    return errors


def check_links(root: Path) -> list[str]:
    errors: list[str] = []
    for skill in skill_dirs(root):
        for path in sorted(skill.rglob("*.md")):
            # Test fixtures deliberately contain broken links (`missing.md`) to prove the
            # vendored doc checker can go red; they are not real documentation.
            if "fixtures" in path.parts:
                continue
            text = path.read_text(encoding="utf-8")
            for target in LINK_RE.findall(text):
                if target.startswith(("http://", "https://", "mailto:", "#")):
                    continue
                clean = target.split("#", 1)[0]
                if not clean:
                    continue
                if not (path.parent / clean).resolve().exists():
                    relative = path.relative_to(root).as_posix()
                    errors.append(f"{relative}: dead link -> {target}")
    return errors


def check_manifest(root: Path) -> list[str]:
    manifest = root / SKILLS_ROOT / MANIFEST_NAME
    if not manifest.is_file():
        return [f"{SKILLS_ROOT}/{MANIFEST_NAME} missing"]
    errors: list[str] = []
    declared: dict[str, str] = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        digest, _, relative = line.partition("  ")
        if not relative:
            errors.append(f"manifest line malformed: {line[:60]}")
            continue
        declared[relative] = digest
    for relative, digest in sorted(declared.items()):
        path = root / relative
        if not path.is_file():
            errors.append(f"manifest lists missing file: {relative}")
            continue
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            errors.append(f"manifest mismatch: {relative}")
    for skill in skill_dirs(root):
        for path in sorted(skill.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            if relative not in declared:
                errors.append(f"file not in manifest: {relative}")
    return errors


def check(root: Path) -> list[str]:
    return (
        check_structure(root)
        + check_sanitization(root)
        + check_links(root)
        + check_manifest(root)
    )


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _manifest_for(root: Path, paths: list[Path]) -> str:
    lines = [f"{_sha256(path)}  {path.relative_to(root).as_posix()}" for path in paths]
    return "\n".join(sorted(lines)) + "\n"


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        good = root / SKILLS_ROOT / "good"
        skill_md = good / "SKILL.md"
        reference = good / "references" / "a.md"
        _write(
            skill_md,
            "---\n"
            "name: good\n"
            "description: A valid skill used as the positive control.\n"
            "---\n\n"
            "# Positive control\n\n"
            "This fixture is deliberately longer than the minimum size so the structure\n"
            "check can tell a real skill apart from a stub. It links to\n"
            "[the reference](references/a.md) and names `.agents/skills/good/SKILL.md`,\n"
            "i.e. the repo-relative layout, which sanitization must NOT flag.\n\n"
            "## Workflow\n\n"
            "1. Read the reference.\n"
            "2. Run the checker and require exit code 0.\n",
        )
        _write(reference, "# Reference\n\nEnough text to be a real file.\n")
        _write(
            root / SKILLS_ROOT / MANIFEST_NAME,
            _manifest_for(root, [skill_md, reference]),
        )

        errors = check(root)
        if errors:
            print("SELF-TEST FAIL: false positive on a valid tree:")
            for error in errors:
                print("  " + error)
            return 4

        bad = root / SKILLS_ROOT / "bad"
        _write(
            bad / "SKILL.md",
            "---\n"
            "name: wrong\n"
            "description: Negative control with host paths and a dead link.\n"
            "---\n\n"
            "Open C:\\Users\\dev\\secret.kt, D:\\work\\.agents\\skills\\leak\\SKILL.md\n"
            "and [dead](missing.md).\n",
        )
        errors = check(root)
        required = (
            "forbidden windows user path",
            "forbidden host skill path",
            "dead link -> missing.md",
            "does not match directory",
        )
        missing = [needle for needle in required if not any(needle in error for error in errors)]
        if missing:
            print("SELF-TEST FAIL: negative control not detected:", missing)
            print("  got:", errors)
            return 4

        _write(reference, "# Reference\n\nTampered after the manifest was written.\n")
        errors = check(root)
        if not any("manifest mismatch" in error for error in errors):
            print("SELF-TEST FAIL: manifest mismatch not detected")
            return 4

    print("self-test OK (positive control clean, negative controls red)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    errors = check(ROOT)
    if not errors:
        print(f"skills integrity: {len(skill_dirs(ROOT))} skills clean")
        return 0
    structural = [error for error in errors if not error.startswith("manifest")]
    print(f"skills integrity: {len(errors)} problem(s)")
    for error in errors:
        print("  " + error)
    return 3 if not structural else 2


if __name__ == "__main__":
    sys.exit(main())
