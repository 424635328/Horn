# Contributing to Horn

Thanks for your interest in contributing! This project takes a deliberately minimal user-facing
philosophy ("press to honk, release to stop") and a deliberately maximal engineering standard
(real-time DSP, zero-GC audio threads, architecture gates, visual regression CI). Please read this
guide before opening a PR.

## Development environment

| Requirement | Version |
|---|---|
| JDK | 21 (Temurin recommended) |
| Android Studio | Ladybug or newer |
| Android SDK | platform 36, build-tools 36.x |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| Gradle | 8.13 (wrapper) |

The NDK and CMake versions are pinned in `core/audio/build.gradle.kts`; the CI installs exactly
those versions.

## Branching and commits

- Branch from `main`; keep PRs focused and reviewable.
- Commit messages follow Conventional Commits:
  `type(scope): summary`, where `type` is one of
  `feat|fix|perf|refactor|ui|audio|docs|test|build|ci|chore`.
- The PR title must also pass the Conventional Commit check (`.github/workflows/pr-hygiene.yml`).

## Local quality gates

The project is **local-first**: run the full gate locally before pushing — CI re-runs the same
command as a lightweight verification, not as the first line of defense.

```bash
git config core.hooksPath .githooks   # install the pre-push hook (once per clone)
./gradlew checkAll                    # every gate below, one command
```

The pre-push hook runs `checkAll` plus the docs link and vendored-skills integrity checks
automatically (bypass in an emergency with `git push --no-verify`). Individual gates:

```bash
./gradlew spotlessApply               # auto-format Kotlin / Gradle / misc files
./gradlew spotlessCheck detekt        # format + static analysis
./gradlew testDebugUnitTest           # all unit tests incl. Konsist architecture tests
./gradlew verifyRoborazziDebug        # visual regression against committed baselines
./gradlew lintDebug                   # Android Lint, zero errors
python .github/scripts/check_docs_links.py   # docs zero-dead-link check
python .github/scripts/check_skills.py       # skills structure + sanitization + manifest
```

CI is **incremental**: the `changes` job maps your diff (`.github/scripts/impact_map.py`) to the
affected modules and runs only those checks — docs-only PRs cost a format + link check, while
build-config changes conservatively escalate to the full battery. The `release-build-and-size`
job only runs when release-affecting paths change. A weekly `full-suite.yml` run (Mon 02:00 UTC,
or `workflow_dispatch`) is the complete-battery backstop, so `checkAll` locally remains the
authoritative standard.

## Adding a new horn voice

1. Extend `HornType` in `core/audio` and implement a synthesizer following the existing
   `HornSynthesizer` contract (pre-allocated tables, no allocation in the render path).
2. Calibrate attack/release, makeup gain and verify equal-loudness balance in the DSP tests
   (`core/audio/src/test`). The tests assert no NaN/Inf, no clipping beyond `[-1, 1]`, and
   consistent RMS targets.
3. Add a vector icon in `core/designsystem` (`ic_type_*.xml`) and localized strings
   (`feature/horn/src/main/res/values/strings.xml`, `feature/horn/src/main/res/values-zh/strings.xml`).
4. Add a haptic signature in `core/haptic`.
5. Re-record Roborazzi baselines (see below) and update `docs/spec/02-audio-dsp-engine.md`.

## Roborazzi baselines

- After an intentional UI change, re-record and verify:

```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

- Baselines are font-rasterization sensitive. If CI reports mismatches that you cannot reproduce
  locally, run the `.github/workflows/update-screenshots.yml` workflow manually and commit the
  Linux-recorded baselines it produces.

## Specs and ADRs

Architecture contracts live in `docs/spec/`. If your change modifies a module boundary, an audio
thread rule, a lifecycle safety contract, or another system invariant, update the relevant spec
document — or add a new MADR-style ADR under `docs/spec/adr/` — in the same PR.

## Vendored agent skills (`skills/`)

`skills/` is a **read-only, sanitized snapshot** of the agent skills this project actually used
(doc writing, primary-source research, startup-animation methodology). It is development-time
material only: it is not a Gradle module, not packaged into any artifact, and excluded from
spotless so the vendored bytes stay reproducible.

- Do not edit vendored content in place. Fix it upstream in the skill library and re-snapshot;
  the only allowed local edits are sanitization and repo-relative path fixes.
- Every re-snapshot must regenerate `skills/manifest.sha256`, update the snapshot date and the
  sanitization table in `skills/README.md`, and pass `python .github/scripts/check_skills.py`.
- Adding a skill requires a concrete artifact it produced in this repo (file, PR or ADR); skills
  that were only tried and produced nothing are not vendored (`archify` was rejected this way).

## Comment feedback

PR comments from humans get one reaction from the bot (`comment-feedback.yml`) as a quiet
"received" signal — no reply, no thread noise. The palette is `+1 / rocket / eyes / heart /
hooray`, picked deterministically from the comment id, and can be overridden with the repository
variable `COMMENT_REACTION_EMOJIS`. Bot comments are ignored, so Dependabot's own acknowledgement
is never echoed.

## Safety red lines

- Never allocate, lock, log or do I/O on the audio render callback path.
- Never break the anti-stuck-audio contract (background, focus loss, gesture cancel must all stop
  playback safely).
- Never let debug/diagnostic code reach release builds (use `src/debug` vs `src/release`).
- Never commit keystores, tokens or other secrets.

## License

By contributing, you agree that your contributions are licensed under the project's MIT License.
