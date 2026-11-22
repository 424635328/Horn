# Pull Request

## Summary

<!-- What does this change do, and why? -->

Closes #

## Change type

- [ ] Audio DSP
- [ ] Apple Glass UI & Motion
- [ ] Wear OS
- [ ] Glance / Quick Tile
- [ ] Spec & ADR
- [ ] CI & DX
- [ ] Bug Fix

## Visual / acoustic evidence

<!-- UI changes: before/after screenshots or a Roborazzi diff summary.
     Audio changes: DSP metrics or Debug oscilloscope verification. -->

## Core invariants self-check

- [ ] Audio callback thread stays strictly zero-allocation (Zero-GC) and lock-free
- [ ] Anti-stuck-audio safety contract holds for background, focus loss, gesture cancel and slide-out
- [ ] High-frequency motion and RMS visuals use deferred-state reads (no recomposition storms; ambient light stops in background)
- [ ] Release builds contain zero debug overlay / diagnostic code leakage
- [ ] If module contracts or key decisions changed, `docs/spec/` or a MADR ADR is updated in this PR

## Local gate checklist

- [ ] `./gradlew spotlessApply`
- [ ] `./gradlew spotlessCheck detekt`
- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew verifyRoborazziDebug` (baselines re-recorded when UI intentionally changed)
- [ ] `./gradlew lintDebug`
