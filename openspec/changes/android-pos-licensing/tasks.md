# Tasks: android-pos-licensing (offline license enforcement)

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~2500-4000 (new `licensing/` package + Room migration + `MainActivity`/`PosApplication`/`AppContainer` wiring + R8/ProGuard config + cross-repo CLI diff, each with matching tests) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 → PR 8 (see Suggested Work Units) |
| Delivery strategy | auto-chain (cached) |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High

From-scratch security-sensitive subsystem: RSA/JWS crypto, Keystore-backed identity, a Room migration, a **global** R8 flip requiring a full-app smoke pass, and a cross-repo CLI change in a separate repository. One PR would bury a reviewer in unrelated concerns (crypto correctness vs. Compose UI vs. build-config regression risk). Chaining is required at this size, same as `android-pos-mvp`'s 8-PR split.

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Config scope + pure domain (Phase 0-1) | PR 1 | No Android framework deps; base for all |
| 2 | Installation identity + Room schema (Phase 2) | PR 2 | Depends on PR 1 |
| 3 | LicenseRepository orchestration (Phase 3) | PR 3 | Depends on PR 1, PR 2 |
| 4 | Activation UI (Phase 4) | PR 4 | Depends on PR 3 |
| 5 | Enforcement gate wiring (Phase 5) | PR 5 | Depends on PR 3, PR 4 |
| 6 | R8 flip + full-app smoke pass (Phase 6) | PR 6 | Depends on PR 5; app must be feature-complete to smoke-test meaningfully |
| 7 | Cross-repo CLI `--product` (Phase 7) | PR 7 | Separate repo (`idos-pos`), separate PR — gates Android issuance, not Android code |
| 8 | Docs + integration pass (Phase 8) | PR 8 | Depends on PR 6, PR 7 |

## Phase 0: TDD Scope Config

- [x] 0.1 Edit `openspec/config.yaml`: add `"licensing/**"` to `strict_tdd_scope.include`

## Phase 1: Pure License Domain (RED/GREEN)

- [x] 1.1 RED: `LicenseStatus.kt` test — 9-variant enum (VALID/IN_GRACE_PERIOD/EXPIRED/COMPROMISED/NOT_CONFIGURED/INVALID_SIGNATURE/WRONG_PRODUCT/MACHINE_MISMATCH/MALFORMED)
- [x] 1.2 RED: `LicenseVerifierTest.kt` — signature valid/tampered, product match/mismatch, machine-id match/mismatch, `limits{}` ignored, expiry VALID/boundary/GRACE/EXPIRED, malformed JWS
- [x] 1.3 GREEN: `licensing/LicenseVerifier.kt` — `verify(jws, installationId, nowUtc, compromised)` per design's 6-step algorithm; loads `res/raw` DER pubkey via `X509EncodedKeySpec`
- [x] 1.4 RED/GREEN: `licensing/InstallationId.kt` — pure `derive(androidId, uuid) = hex(sha256(...))`, deterministic

## Phase 2: Installation Identity + Room Schema

- [x] 2.1 Instrumented test (alongside, not RED/GREEN — Keystore I/O, per design's testing strategy): `InstallationIdStoreTest` — attempted under Robolectric; confirmed the same `KeyStoreException`/`NoSuchAlgorithmException` environment limitation `PinHasher`'s KDoc documents for `PinRepository` (Keystore unavailable under Robolectric here), so the probe test was removed rather than left permanently failing — zero automated coverage for this class, same as `PinRepository`, by design
- [x] 2.2 `licensing/InstallationIdStore.kt` — EncryptedSharedPreferences UUID gen/persist + `Settings.Secure` read
- [x] 2.3 RED: `ClockRollbackDetectorTest.kt` — rollback beyond tolerance flags, drift within tolerance doesn't, sticky-compromised write
- [x] 2.4 GREEN: `licensing/ClockRollbackDetector.kt` — pure triangulation, `toleranceSec` param
- [x] 2.5 `licensing/LicenseStateEntity.kt` + `LicenseStateDao.kt` — single-row `license_state` table
- [x] 2.6 RED/GREEN (Robolectric+in-mem Room): `LicenseStateDaoTest.kt` — insert/update single row; `MIGRATION_1_2` applies cleanly to a v1 fixture
- [x] 2.7 `core/db/PosDatabase.kt`: v1→v2, register `LicenseStateEntity`/`LicenseStateDao`, add `MIGRATION_1_2`

## Phase 3: License Repository Orchestration

- [x] 3.1 RED: `LicenseRepositoryTest.kt` (fakes for dao/verifier/idStore/detector) — `preview()` no-persist, `install()` clears compromised, strict rollback guard (`candidateIssuedAt <= storedIssuedAt` rejected; first activation with no stored license passes), `currentStatus()` COMPROMISED overrides expiry status, `heartbeat()` triangulates+persists
- [x] 3.2 GREEN: `licensing/LicenseRepository.kt` — `installationId()`, `currentStatus()`, `preview(jws)`, `install(jws)`, `heartbeat()`; named `GRACE_PERIOD_DAYS = 3L`/`CLOCK_TOLERANCE_SECONDS = 60L` constants, never inlined
- [x] 3.3 Wire `licenseStateDao`, `licenseRepository` into `core/di/AppContainer.kt` (lazy, existing pattern) — also registers `MIGRATION_1_2` on the production `Room.databaseBuilder` call and wires a **placeholder** `res/raw/idos_vendor_pub` DER public key (see deviation note in the apply report / `AppContainer.kt` KDoc — must be replaced with the real vendor key before any signed build)

## Phase 4: Activation UI

- [ ] 4.1 `licensing/ActivationViewModel.kt` — file-import (SAF `OpenDocument`) / paste-text → preview → confirm → install; distinguishable error per rejection reason (signature/product/binding/replay/expired)
- [ ] 4.2 `licensing/ActivationScreen.kt` — shows installation-ID (copyable), preview result, confirm/install action
- [ ] 4.3 Compose UI test (alongside): `ActivationScreenTest.kt` — preview shows claims without installing; install success unlocks; install failure shows rejection reason; file and paste paths produce equivalent outcomes

## Phase 5: Enforcement Gate Wiring

- [ ] 5.1 `MainActivity.kt`: extract current Phase-0 content into `AppRoot()`; add `if (licensed) AppRoot() else ActivationScreen()` boolean gate; `onResume` calls `licenseRepository.heartbeat()`
- [ ] 5.2 `PosApplication.kt`: startup `currentStatus()` eval before first composition
- [ ] 5.3 Persistent warning banner in `AppRoot()` when status is `IN_GRACE_PERIOD`
- [ ] 5.4 UI test (alongside): gate renders `AppRoot()` for VALID/GRACE, `ActivationScreen` for NOT_CONFIGURED/EXPIRED/COMPROMISED with no escape path; existing POS data intact across a gate flip

## Phase 6: R8 Flip + Mandatory Full-App Smoke Pass

- [ ] 6.1 `app/build.gradle.kts`: `release { isMinifyEnabled = true }` (global flip, not licensing-scoped)
- [ ] 6.2 `proguard-rules.pro`: `-keep class com.idos.pos.licensing.** { *; }` precautionary rule
- [ ] 6.3 **MANDATORY, not optional/deferrable**: build a signed release APK and run a full-app smoke pass — barcode scan (MLKit/CameraX), product CRUD, a completed sale, cash-session open/close, AND activation/preview/install/gate. Add any missing keep rules (MLKit/CameraX/Room) this surfaces; do not defer silently to a later change

## Phase 7: Cross-Repo CLI Extension (`idos-pos` repo — separate PR)

- [ ] 7.1 **Separate-repo work, not part of this Android PR chain**: in `D:\Proyectos\idos-pos\tools\license-cli\LicenseCli.java`, add `--product` flag (default `backend`); when `product != backend`, skip `limitsClaim`/`limitsFor(plan)` validation, default `--plan` to `"none"`
- [ ] 7.2 Add `--version` flag printing CLI version + supported products (`backend`, `android-pos`)
- [ ] 7.3 Update `tools/license-cli/README.md`: Android issuance SOP (`--product android-pos --machine-id <installationId> --expires YYYY-MM-DD --out idos.lic`) + manual `--version` drift-check step
- [ ] 7.4 Lands as its own PR in the `idos-pos` repository — never bundled into the same commit/PR as any Android task above

## Phase 8: Integration & Documentation

- [ ] 8.1 Record final resolved constants in `openspec/project.md` (new section, extending the existing "Bootstrap Decisions" pattern): `GRACE_PERIOD_DAYS=3`, `CLOCK_TOLERANCE_SECONDS=60`, public-key encoding = X.509 DER via `res/raw` + `X509EncodedKeySpec`
- [ ] 8.2 End-to-end Robolectric pass covering proposal Success Criteria: unlicensed boot → activation screen with installation-ID; product-bound install unlocks; wrong-product/wrong-binding rejected; rollback → COMPROMISED re-blocks; expired-beyond-grace hard-blocks, in-grace runs with banner
