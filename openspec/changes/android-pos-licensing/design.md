# Design: android-pos-licensing (offline license enforcement)

## Technical Approach

A new `com.idos.pos.licensing` package ports the reference desktop system's *shape* (JWS verify → installation binding → status enum → startup gate → anti-tamper heartbeat) using only platform APIs — `java.security.Signature`, `MessageDigest`, `org.json` (all in `android.jar`, no new Gradle deps). Layering mirrors the existing per-feature convention (`Entity/Dao → Repository → ViewModel/Screen`, wired lazily in `core/di/AppContainer.kt`). All firm decisions in `proposal.md` are locked; this document is HOW only. POS domain shape is untouched — licensing hooks `MainActivity` (boolean composable swap) + `PosApplication` + one additive Room table.

## Architecture Decisions

| # | Decision | Choice | Rejected | Rationale |
|---|----------|--------|----------|-----------|
| A | JWS verify stack | Hand-rolled `Signature("SHA256withRSA")` + `org.json` field-by-field parse | jjwt/Jackson | Zero new deps; parse is explicit (`getString("iss")`), not reflective — survives R8 with no keep rules |
| B | Public key delivery + encoding | `res/raw/idos_vendor_pub` as **X.509 DER binary** (SubjectPublicKeyInfo), loaded `openRawResource(R.raw.idos_vendor_pub).readBytes()` → `X509EncodedKeySpec(bytes)` → `KeyFactory.getInstance("RSA").generatePublic(spec)` | PEM text (strip `-----BEGIN/END-----` + newlines → Base64-decode → same `X509EncodedKeySpec`); hardcoded Base64 in code | DER is exactly what `X509EncodedKeySpec` expects — no header-strip/Base64 pre-parse step, one less failure mode. Resource generated once from the vendor `.pub` via `openssl rsa -pubin -inform PEM -outform DER -in idos-vendor.pub -out idos_vendor_pub`. (PEM was the runner-up only because it eyeball-verifies against the vendor key file; the extra text-parsing wasn't worth it for a build-time-fixed resource) |
| C | Gate mechanism | `MainActivity` `if (licensed) AppRoot() else ActivationScreen()` | Nav-graph route guard | No nav graph exists; reuses `PosScreen`'s `isScanning` boolean-swap idiom (Decision #4). `AppRoot()` = extract of today's Phase-0 `MainActivity` content, renamed — NO shell/nav introduced |
| D | Heartbeat trigger | `MainActivity.onResume` → `LicenseRepository.heartbeat()` (single-activity app) + one startup tick | `WorkManager`/`@Scheduled`/`ProcessLifecycleOwner` | Foreground-only per proposal; `ProcessLifecycleOwner` = new lib (forbidden) |
| E | Pure-vs-Keystore split | Pure logic (`LicenseVerifier`, `ClockRollbackDetector`, `InstallationId.derive`) separated from Keystore/`Settings.Secure` I/O (`InstallationIdStore`, license prefs mirror) | One monolithic repo | Mirrors MVP's `PinHasher`(JVM) vs `PinRepository`(instrumented) split — pure parts get RED/GREEN unit coverage |
| F | Grace & tolerance values | **`graceDays = 3`, `toleranceSec = 60`** as named `const val` on `LicenseRepository` (`GRACE_PERIOD_DAYS = 3L`, `CLOCK_TOLERANCE_SECONDS = 60L`), passed into `LicenseVerifier`/`ClockRollbackDetector` — never inlined in comparison logic | Hardcoded literals inside `!today.isAfter(exp)` / `now < lowerBound - x` | Reuses the reference desktop defaults (`IDOS_LICENSE_GRACE_PERIOD_DAYS=3`, `IDOS_LICENSE_TIME_TOLERANCE_SECONDS=60` per idos-pos CLAUDE.md); the two specs explicitly delegated the choice to sdd-design (`license-verification/spec.md:71`, `anti-tamper-heartbeat/spec.md:57`). Named constants keep the values configurable and give sdd-tasks a concrete unit to slice |

## Claim shape + verification algorithm

Payload = desktop shape **minus `limits{}`, plus mandatory `product`**:
`iss`, `sub`(licenseId), `iat`, `exp`, `version`, `plan`, `business{id,name,taxId}`, `machineId`(carries installation-ID), `product`("android-pos").

`LicenseVerifier.verify(jws, installationId, nowUtc, compromised)` steps:
1. Split on `.` → 3 parts; base64url-decode header+payload. Fewer/more than 3 parts, or a decode failure at any step → `MALFORMED`.
2. Header `alg == "RS256"` else reject.
3. `Signature("SHA256withRSA")`.initVerify(pub); update(`header.'.'.payload` ASCII); verify(decoded sig) → else `INVALID_SIGNATURE`.
4. `JSONObject(payload)`: `iss == expectedIssuer`, `product == "android-pos"`, `machineId == installationId` → else `WRONG_PRODUCT`/`MACHINE_MISMATCH`.
5. `limits` ignored if present.
6. Status: `compromised` (sticky) → `COMPROMISED`; else compare `exp` (LocalDate, `ZoneOffset.UTC`, `!today.isAfter(exp)`) → `VALID`; within `graceDays` → `IN_GRACE_PERIOD`; beyond → `EXPIRED`. No stored token → `NOT_CONFIGURED`.

`licensed = status ∈ {VALID, IN_GRACE_PERIOD}`.

## Installation identity

`InstallationId.derive = hex(sha256(ANDROID_ID + uuid))`. `uuid` = `UUID.randomUUID()` generated once on first access and persisted in **`EncryptedSharedPreferences("idos-pos-license-prefs")`** (same `MasterKey` AES256_GCM / SIV pattern as `PinRepository`). `ANDROID_ID` via `Settings.Secure`. Binding check = claim `machineId == derive()`.

## Clock-rollback triangulation (`ClockRollbackDetector`, pure)

`lowerBound = max(licenseIssuedAt, roomLastHeartbeat, prefsLastHeartbeat)`. If `now < lowerBound - toleranceSec` → rollback → set `compromised=true` (sticky, written to Room **and** prefs mirror). Else write `now` to both. Cleared only by a successful `install`.

## Data flow

```
startup ─▶ LicenseRepository.currentStatus() ─▶ MainActivity
             │                                    if licensed → AppRoot()
             │                                    else        → ActivationScreen()
onResume ─▶ heartbeat() ─▶ ClockRollbackDetector ─▶ (Room + prefs mirror)

ActivationScreen: shows installationId ─▶ pick file / paste text
   ─▶ preview()  = verify WITHOUT persist  ─▶ show business/exp/product/match
   ─▶ install()  = rollback-guard → persist JWS + clear compromised → re-eval → gate flips
```

### Rollback guard (strict, per `license-activation/spec.md:33-35`)

`install(candidate)` computes `candidateIssuedAt` from the verified `iat` claim, then:
- **Reject** when `candidateIssuedAt <= storedIssuedAt` (raises a rollback `DomainError`, no persist, active license untouched). The guard passes ONLY on strict `candidateIssuedAt > storedIssuedAt`.
- **First activation** (no license currently stored → `storedIssuedAt == null`): the guard always passes, since there is nothing to compare against.

Rejecting on equality is deliberate: reinstalling an identical-`iat` license is a replay and the spec's MUST forbids it.

## File Changes

| Path | Action | Description |
|------|--------|-------------|
| `licensing/LicenseVerifier.kt` | Create | Pure JWS verify + claim parse + status eval |
| `licensing/LicenseStatus.kt` | Create | Enum: VALID/IN_GRACE_PERIOD/EXPIRED/COMPROMISED/NOT_CONFIGURED (+ reject reasons) |
| `licensing/InstallationId.kt` | Create | Pure `derive(androidId, uuid)` |
| `licensing/InstallationIdStore.kt` | Create | UUID gen/persist (EncryptedSharedPreferences) + `Settings.Secure` read |
| `licensing/ClockRollbackDetector.kt` | Create | Pure triangulation |
| `licensing/LicenseStateEntity.kt`, `LicenseStateDao.kt` | Create | Single-row `license_state` table |
| `licensing/LicenseRepository.kt` | Create | preview/install/currentStatus/heartbeat; rollback guard; `Result<T>`+`DomainError` |
| `licensing/ActivationViewModel.kt`, `ActivationScreen.kt` | Create | File-import (SAF `OpenDocument`) / paste + preview-confirm; shows installation-ID |
| `MainActivity.kt` | Modify | Extract content to `AppRoot()`; add boolean gate + `onResume` heartbeat |
| `PosApplication.kt` | Modify | Startup status eval |
| `core/di/AppContainer.kt` | Modify | `licenseStateDao`, `licenseRepository` (lazy) |
| `core/db/PosDatabase.kt` | Modify | v1→v2, register entity/dao, `MIGRATION_1_2` |
| `app/build.gradle.kts` | Modify | `release { isMinifyEnabled = true }` |
| `proguard-rules.pro` | Modify | Precautionary keep rules (below) |
| `idos-pos/tools/license-cli/LicenseCli.java` | Modify (cross-repo) | `--product` + `--version` |

## Room schema (v2, additive)

| Table | Key | Cols |
|-------|-----|------|
| `license_state` | `id` (PK=1, single row) | `jws` TEXT?, `installedIssuedAt` Long?, `lastHeartbeatAt` Long?, `compromised` BOOL |

`MIGRATION_1_2 = CREATE TABLE license_state(...)`. No existing table touched.

## Interfaces

```kotlin
enum class LicenseStatus { VALID, IN_GRACE_PERIOD, EXPIRED, COMPROMISED, NOT_CONFIGURED,
                           INVALID_SIGNATURE, WRONG_PRODUCT, MACHINE_MISMATCH, MALFORMED }
class LicenseRepository(dao, verifier, idStore, prefsMirror, detector) {
    companion object {
        const val GRACE_PERIOD_DAYS = 3L      // reuse desktop IDOS_LICENSE_GRACE_PERIOD_DAYS
        const val CLOCK_TOLERANCE_SECONDS = 60L // reuse desktop IDOS_LICENSE_TIME_TOLERANCE_SECONDS
    }
    fun installationId(): String
    suspend fun currentStatus(): LicenseStatus
    fun preview(jws: String): Result<LicensePreview>       // verify, no persist
    suspend fun install(jws: String): Result<LicenseStatus> // strict rollback-guard (candidateIat > storedIat) + persist + clear compromised
    suspend fun heartbeat()                                 // triangulate; sticky COMPROMISED
}
```

## R8 / ProGuard

**The flip is GLOBAL, not licensing-scoped.** `isMinifyEnabled = true` on `release {}` obfuscates/shrinks the ENTIRE app for the first time — CameraX, **MLKit barcode scanning**, Jetpack Compose, `security-crypto`, and every existing screen (`PosScreen`, `ProductListScreen`, etc.) are minified as a side effect of this change, not just the new `licensing/` package.

Licensing code survivability is high on its own: crypto uses JCA string lookups, org.json parsing is manual (no reflective binding), and org.json is a platform class (not in the APK, not obfuscated). Precautionary licensing keep: `-keep class com.idos.pos.licensing.** { *; }` (guards a future reflective refactor + `enum valueOf`).

**Other risk surface — MLKit and CameraX.** MLKit has known R8 friction (reflection-loaded model classes); CameraX is the adjacent concern. This change's verification MUST include a **full-app smoke pass against a signed release APK**, not just the activation/verify flows: exercise barcode scan, product CRUD, a completed sale, and cash-session open/close. If MLKit/CameraX break under obfuscation, adding their keep rules is **in scope for this change** — do not defer silently.

Room is expected to ship its own `consumer-rules.pro` in `room-runtime` (known AndroidX Room ≥2.1 behavior — NOT verified against this project's specific AAR; covered by the required full-app release-APK smoke check above). No manual Room keeps planned unless that smoke check proves otherwise.

## Cross-repo CLI (`idos-pos/tools/license-cli`)

- Add `--product` (default `backend`). When `product != backend`: **skip `limitsClaim` and the `limitsFor(plan)` enum validation**; `--plan` defaults to `"none"` (expiration-only). Always emit a `product` claim (default `backend` preserves desktop). Android issuance: `--product android-pos --machine-id <installationId> --expires YYYY-MM-DD --out idos.lic` (no `--plan`).
- Add `--version` printing CLI version + supported products (`backend`, `android-pos`). Vendor SOP: run `--version` and confirm `android-pos` listed before Android issuance (manual drift check, no automated coupling).

## Testing Strategy (mapped to `strict_tdd_scope`)

Recommend adding `licensing/**` to `strict_tdd_scope.include` (existing `**/*ViewModel.kt`/`**/*Screen.kt`/`core/di/**` excludes still carve out UI/wiring).

| Layer | Class | Mode |
|-------|-------|------|
| RED/GREEN (JVM, JUnit4) | `LicenseVerifier` (sig/product/machine/expiry/grace), `ClockRollbackDetector`, `InstallationId.derive`, `LicenseRepository` (rollback guard, status selection — fakes), `LicenseStateDao` (Robolectric+in-mem Room) | test-first |
| Alongside (instrumented) | `InstallationIdStore`, prefs mirror (Keystore — not Robolectric, per `PinRepository` precedent) | with impl |
| Alongside (Compose) | `ActivationScreen`, `ActivationViewModel`, gate swap | with impl |

Test-only RSA keypair generated at runtime (like MVP's zxing fixture) signs fixtures — never ship a private key.

## Migration / Rollout

Additive Room `MIGRATION_1_2`; revert = drop commits + migration, app returns to unlicensed-MVP state. CLI PR reverts independently (`--product backend` default preserves desktop).

## Open Questions

None. Both previously-open items are now resolved in this document:
- `graceDays` / `toleranceSec` → **Decision F** (`3` days / `60` s, named constants on `LicenseRepository`).
- Public-key resource encoding → **Decision B** (X.509 DER binary in `res/raw`, `X509EncodedKeySpec` load path).
