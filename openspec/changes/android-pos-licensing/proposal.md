# Proposal: android-pos-licensing (offline license enforcement for the Android POS)

## Intent

`android-pos-mvp` shipped the POS core with a **binding non-goal: no build may reach a paying customer until licensing lands**. This change lifts that gate: an offline, on-device license subsystem (RSA-SHA256 JWS verify, installation-ID binding, activation UI, clock-rollback anti-tamper, and a startup enforcement gate) that **raises the bar against casual/accidental unlicensed use** — it deters a customer from freely copying and sharing the APK, it does **not** stop a determined attacker (see Risks: the check is an on-device gate in a sideloaded, unobfuscated APK with no remote attestation). It ports the reference desktop system's *shape*, not its implementation.

## Scope

### In Scope
- On-device JWS verify (manual `Signature` + `org.json`, no jjwt/Jackson) against a baked-in vendor public key.
- Composite **installation-ID** derivation + binding check.
- Activation flow (file-import / paste-text): preview + install, showing the installation-ID to report to the vendor.
- Top-level **enforcement gate** in `MainActivity` (boolean composable swap, no nav graph) blocking the POS until VALID/IN_GRACE_PERIOD.
- Foreground-triggered anti-tamper heartbeat (Room + EncryptedSharedPreferences + signed `issuedAt`), sticky COMPROMISED.
- **Enable R8 minify + obfuscate** for the `release` build type (`isMinifyEnabled = true`) — near-zero-cost partial mitigation that raises the reverse-engineering bar. It does **not** close the gap: without remote attestation a determined attacker still locates and patches out an on-device check regardless of obfuscation.
- **Cross-repo:** extend `tools/license-cli` (in `idos-pos`) with a `--product` flag + Android issuance docs.

### Out of Scope
- QR-scan activation (file-import/paste only for v1).
- Automated re-activation telemetry / server-side abuse detection (offline product — manual vendor process).
- Multi-device/`limits{}` enforcement, background `WorkManager` scheduling.
- Any change to POS domain shape (Product/Inventory/Order/CashSession untouched).

## Capabilities

### New Capabilities
- `license-verification`: JWS RSA-SHA256 verify, claim parse, status evaluation (VALID/GRACE/EXPIRED/COMPROMISED/NOT_CONFIGURED).
- `installation-identity`: composite installation-ID derivation, persistence, binding check.
- `license-activation`: file-import/paste preview + install, installation-ID display.
- `license-enforcement-gate`: top-level `MainActivity` boolean composable swap (licensed → app entry, else → activation), no nav graph.
- `anti-tamper-heartbeat`: foreground clock-rollback triangulation, sticky COMPROMISED.
- Cross-repo CLI issuance (`--product`) is tracked here but lands in `idos-pos` — not an Android spec file.

### Modified Capabilities
None (greenfield in this repo).

## Decisions (firm — sdd-design details HOW, not WHICH)

| # | Decision | Choice | Confirm/Override | Rationale |
|---|----------|--------|------------------|-----------|
| 1 | Keypair + cross-product isolation | **Shared vendor keypair + mandatory `product="android-pos"` claim**; Android rejects any license lacking it | Confirm | One key = one secret/rotation surface for a solo vendor; a desktop `.lic` (no/`backend` product) fails Android's check. Desktop-side `product` enforcement is a recommended non-blocking cross-repo follow-up. |
| 2 | Installation identity | `sha256(ANDROID_ID + first-run random UUID)`, UUID in EncryptedSharedPreferences | Confirm | Mirrors desktop's multi-source-then-persist. Factory reset, reinstall (allowBackup=false wipes UUID), or device swap → new ID → **new license required** via manual vendor re-issuance. ANDROID_ID factory-reset instability accepted as explicit tradeoff (parallels desktop's machine.id-copy tradeoff). No automated grace path. |
| 3 | CLI change | **Extend `tools/license-cli` in place**: add `--product` (default `backend`), reuse `--machine-id` for the installation-ID | Confirm | One signing codebase = one private-key + audit surface. Owned by this change, lands as a coordinated PR in the separate `idos-pos` repo (a tracked dependency, CLI version bumped). |
| 4 | Enforcement gate mechanism + UX | **Top-level boolean composable swap in `MainActivity`**: `if (licensed) AppRoot() else ActivationScreen()`. Hard block when not VALID/GRACE; GRACE = full access + warning banner | Confirm | **No nav graph exists in this codebase** (`MainActivity.kt` is a Phase-0 placeholder; `navigation-compose` is declared-but-unused; every `*Screen.kt` docstring notes "no navigation graph exists yet"). Rather than build the app's first navigation shell as a side effect of licensing (unscoped work), the gate reuses the exact boolean-swap pattern `PosScreen.kt` already uses internally (`isScanning` toggling two composables) — zero new infrastructure. Same *effect* as desktop's `LicenseEnforcementFilter` (activation screen is the only reachable route when unlicensed), without the server-adjacent filter shape. No read-only escape hatch — on-device data is preserved and returns intact after re-activation. **`AppRoot()` here is whatever the licensed entry composable is at implementation time; this change does not introduce a nav graph.** |
| 5 | `limits{}` | **Dropped for Android**; validator ignores it if present, CLI omits it under `--product android-pos` | Confirm | maxStores/maxUsers/maxProducts are meaningless for a single-install product (multi-store permanently out per `android-pos-mvp`). Android plan = expiration-only. Not reserved — a future multi-device story would define its own claim. |

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/.../licensing/` (new package) | New | Verify, identity, activation, gate, anti-tamper |
| `app/.../PosApplication.kt`, `MainActivity.kt` | Modified | Startup status check + top-level `if (licensed) AppRoot() else ActivationScreen()` boolean swap (no nav graph introduced) |
| `app/.../core/di/AppContainer.kt` | Modified | New licensing repo/service slots (lazy pattern) |
| `app/build.gradle.kts` | Modified | `res/raw` public key; flip `release` `isMinifyEnabled = true` (+ ProGuard keep rules for licensing crypto/JSON reflection); no new libraries |
| Room schema | New | `LICENSE_STATE`-equivalent table (heartbeat + status) |
| `idos-pos/tools/license-cli/` | Modified (cross-repo) | `--product` flag + Android issuance docs |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| **APK is sideloaded and unobfuscated — the license check itself can be located and patched out.** The gate is a plainly-named Kotlin function in an APK handed directly to the restricted party; with `isMinifyEnabled=false` (current build) it is trivially readable via `jadx`/`apktool`, and the status branch can be patched and the APK re-signed **without touching the RSA crypto at all**. Fundamentally weaker than desktop, where the check runs server-adjacent, not in the attacker's hands. No Play Integrity / remote attestation to fall back on. | **High** | Partial only: enable R8 minify+obfuscate (in scope) to raise the effort bar. This scheme deters casual/accidental copying; it does **not** stop a determined attacker. Accepted, explicitly disclosed limitation — not claimed as tamper-proof. |
| Uninstall/factory-reset wipes all license state → forced re-activation | Med | Manual single-activation vendor re-issuance; installation-ID shown on activation screen. Accepted, documented tradeoff. |
| Shared keypair lets an Android `.lic` validate on desktop | Low | Add desktop `product` check as cross-repo follow-up; Android side is fully protected. |
| Cross-repo CLI PR not coordinated with this change | Med | Named dependency below; CLI version bump gates issuance. |
| No server telemetry to detect re-activation abuse | Med | Out-of-band vendor process; expiration-bounded licenses limit exposure. |

## Rollback Plan

Greenfield subsystem, isolated in a `licensing` package + one Room table + startup/nav hook. Rollback = revert the change commits and the additive Room migration; POS domain code is untouched, so the app returns to unlicensed-MVP state. The cross-repo CLI PR reverts independently (default `--product backend` preserves existing desktop behavior).

## Dependencies

- Cross-repo PR against `idos-pos/tools/license-cli` (`--product` flag) — must land before Android issuance is possible.
- Vendor RSA keypair (existing desktop keypair reused) + `product` claim convention agreed with the `idos-pos` maintainer.
- `android-pos-mvp` merged (commit `b5eb6cd`).

## Notes for sdd-design

- **CLI `--plan` under `--product android-pos`:** `LicenseCli.java` always builds a `limitsClaim` from `planStr`, but Android has no plan tiers (Decision #5). sdd-design must define a placeholder/default plan value (e.g. `--plan none`/omit → CLI emits an expiration-only claim) so Android issuance doesn't require a meaningless tier.
- **Cross-repo CLI version drift:** nothing technically prevents an operator signing with an old CLI JAR lacking `--product`. sdd-design should specify a lightweight manual verification step (e.g. CLI prints supported products / `--version`, vendor SOP checks it before issuance) — no automated coupling.

## Success Criteria

- [ ] An unlicensed install boots straight to a blocking activation screen showing its installation-ID.
- [ ] A vendor-signed `.lic` with `product="android-pos"` bound to that installation-ID activates and unlocks the POS.
- [ ] A desktop `.lic` (wrong/absent `product`) or one bound to a different installation-ID is rejected.
- [ ] Wall-clock rollback across heartbeats marks the license COMPROMISED and re-blocks until a new license is installed.
- [ ] Expired-beyond-grace license hard-blocks; in-grace license runs with a warning banner.
- [ ] `tools/license-cli --product android-pos` signs a limits-free Android license.
- [ ] The `release` build type ships with `isMinifyEnabled = true` and the licensing crypto/JSON paths survive obfuscation (verified against a signed release APK).
