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
- The PR title must also pass the Conventional Commit check (`pr-hygiene.yml`).

## Local quality gates

Run these before pushing; CI runs the same commands.

```bash
./gradlew spotlessApply               # auto-format Kotlin / Gradle / misc files
./gradlew spotlessCheck detekt        # format + static analysis
./gradlew testDebugUnitTest           # all unit tests incl. Konsist architecture tests
./gradlew verifyRoborazziDebug        # visual regression against committed baselines
./gradlew lintDebug                   # Android Lint, zero errors
```

## Adding a new horn voice

1. Extend `HornType` in `core/audio` and implement a synthesizer following the existing
   `HornSynthesizer` contract (pre-allocated tables, no allocation in the render path).
2. Calibrate attack/release, makeup gain and verify equal-loudness balance in the DSP tests
   (`core/audio/src/test`). The tests assert no NaN/Inf, no clipping beyond `[-1, 1]`, and
   consistent RMS targets.
3. Add a vector icon in `core/designsystem` (`ic_type_*.xml`) and localized strings
   (`values/strings.xml`, `values-zh/strings.xml`).
4. Add a haptic signature in `core/haptic`.
5. Re-record Roborazzi baselines (see below) and update `docs/spec/02-audio-dsp-engine.md`.

## Roborazzi baselines

- After an intentional UI change, re-record and verify:

```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

- Baselines are font-rasterization sensitive. If CI reports mismatches that you cannot reproduce
  locally, run the `update-screenshots.yml` workflow manually and commit the Linux-recorded
  baselines it produces.

## Specs and ADRs

Architecture contracts live in `docs/spec/`. If your change modifies a module boundary, an audio
thread rule, a lifecycle safety contract, or another system invariant, update the relevant spec
document — or add a new MADR-style ADR under `docs/spec/adr/` — in the same PR.

## Safety red lines

- Never allocate, lock, log or do I/O on the audio render callback path.
- Never break the anti-stuck-audio contract (background, focus loss, gesture cancel must all stop
  playback safely).
- Never let debug/diagnostic code reach release builds (use `src/debug` vs `src/release`).
- Never commit keystores, tokens or other secrets.

## License

By contributing, you agree that your contributions are licensed under the project's MIT License.
