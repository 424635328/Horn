#!/usr/bin/env python3
"""Map changed files to CI impact scopes (deterministic, fail-safe).

Design contract (see docs/spec/05):
  * a change set maps to affected modules = changed modules + all transitive dependents;
  * anything touching build configuration is UNKNOWN blast radius -> full battery;
  * docs-only change sets stay cheap (format + link integrity only).

Outputs (written to $GITHUB_OUTPUT when present, else printed):
  mode          full | scoped
  gradle_args   space-separated Gradle task selectors to run
  run_roborazzi true|false
  needs_release true|false

Exit codes: 0 ok, 2 bad input, 4 self-test failed.
"""

from __future__ import annotations

import os
import sys

# module -> gradle project path
MODULES = {
    "core-audio": ":core:audio",
    "core-data": ":core:data",
    "core-haptic": ":core:haptic",
    "core-designsystem": ":core:designsystem",
    "feature-horn": ":feature:horn",
    "app": ":app",
    "wear": ":wear",
    "benchmark": ":benchmark",
    "baselineprofile": ":baselineprofile",
}

# reverse dependency edges: module -> modules that depend on it (dependents)
DEPENDENTS = {
    "core-audio": ["core-data", "core-haptic", "feature-horn", "benchmark", "app"],
    "core-data": ["feature-horn", "wear", "app"],
    "core-haptic": ["feature-horn", "app"],
    "core-designsystem": ["feature-horn", "app"],
    "feature-horn": ["app"],
    "wear": [],
    "benchmark": [],
    "baselineprofile": ["app"],
}

# path prefix -> module (or special markers)
PATH_RULES = [
    ("core/audio/", "core-audio"),
    ("core/data/", "core-data"),
    ("core/haptic/", "core-haptic"),
    ("core/designsystem/", "core-designsystem"),
    ("feature/horn/", "feature-horn"),
    ("app/", "app"),
    ("wear/", "wear"),
    ("benchmark/", "benchmark"),
    ("baselineprofile/", "baselineprofile"),
]

# any of these -> unknown blast radius -> full battery
FULL_TRIGGERS = (
    "gradle/",
    "build-logic/",
    "config/",
    ".github/workflows/",
    "gradle.properties",
    "settings.gradle.kts",
    "build.gradle.kts",
    "libs.versions.toml",
)

# any of these -> release build verification needed
RELEASE_TRIGGERS = (
    "core/audio/",
    "app/",
    "wear/",
    "build-logic/",
    "gradle/",
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
)

UI_SCOPES = {"core-designsystem", "feature-horn", "app"}

FULL_TASKS = "spotlessCheck detekt lintDebug testDebugUnitTest verifyRoborazziDebug"


def classify(paths: list[str]) -> dict:
    if any(p.endswith(FULL_TRIGGERS) or p.startswith(FULL_TRIGGERS) for p in paths):
        return {
            "mode": "full",
            "gradle_args": FULL_TASKS,
            "run_roborazzi": "true",
            "needs_release": "true",
        }

    changed: set[str] = set()
    docs_only = True
    for p in paths:
        matched = False
        for prefix, module in PATH_RULES:
            if p.startswith(prefix):
                changed.add(module)
                matched = True
                docs_only = False
                break
        if not matched and not (p.endswith(".md") or p.startswith("docs/")):
            # unknown code path: fail-safe to full
            return {
                "mode": "full",
                "gradle_args": FULL_TASKS,
                "run_roborazzi": "true",
                "needs_release": "true",
            }

    affected = set(changed)
    for module in changed:
        affected.update(DEPENDENTS.get(module, []))

    if docs_only:
        return {
            "mode": "scoped",
            "gradle_args": "spotlessCheck",
            "run_roborazzi": "false",
            "needs_release": "false",
        }

    tasks = ["spotlessCheck"]
    for module in sorted(affected):
        path = MODULES[module]
        tasks.append(f"{path}:detekt")
        tasks.append(f"{path}:testDebugUnitTest")
        tasks.append(f"{path}:lintDebug")
    run_roborazzi = "true" if affected & UI_SCOPES else "false"
    if run_roborazzi == "true":
        tasks.append("verifyRoborazziDebug")
    needs_release = "true" if any(p.startswith(RELEASE_TRIGGERS) for p in paths) else "false"
    return {
        "mode": "scoped",
        "gradle_args": " ".join(tasks),
        "run_roborazzi": run_roborazzi,
        "needs_release": needs_release,
    }


def write_outputs(result: dict) -> None:
    lines = [f"{key}={value}" for key, value in result.items()]
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
    for line in lines:
        print(line)


def self_test() -> int:
    # Positive control: audio change must fan out to dependents and request release build.
    audio = classify(["core/audio/src/main/cpp/native_engine.cpp"])
    assert audio["mode"] == "scoped", audio
    assert ":feature:horn:testDebugUnitTest" in audio["gradle_args"], audio
    assert ":app:testDebugUnitTest" in audio["gradle_args"], audio
    assert audio["needs_release"] == "true", audio

    # Positive control: UI change must run visual regression but not the release build.
    ui = classify(["feature/horn/src/main/java/x.kt"])
    assert ui["run_roborazzi"] == "true", ui
    assert ui["needs_release"] == "false", ui

    # Negative control: docs-only must not schedule module tests or the release build.
    docs = classify(["docs/spec/README.md", "README.md"])
    assert docs["gradle_args"] == "spotlessCheck", docs
    assert docs["run_roborazzi"] == "false", docs
    assert docs["needs_release"] == "false", docs

    # Fail-safe control: unknown build config always escalates to the full battery.
    assert classify(["gradle/libs.versions.toml"])["mode"] == "full"
    assert classify(["mystery/new-module.kt"])["mode"] == "full"

    print("self-test OK (4 controls)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        try:
            return self_test()
        except AssertionError as failure:
            print(f"SELF-TEST FAIL: {failure}")
            return 4
    if len(sys.argv) < 2:
        print("usage: impact_map.py <changed-files-list> | --self-test", file=sys.stderr)
        return 2
    with open(sys.argv[1], encoding="utf-8") as handle:
        paths = [line.strip() for line in handle if line.strip()]
    write_outputs(classify(paths))
    return 0


if __name__ == "__main__":
    sys.exit(main())
