# First-Run Onboarding Specification (Amended by `android-pos-auth-login-first`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-auth/specs/first-run-onboarding/spec.md`. That spec described a **blocking** first-run flow bundling ADMIN seeding + business-profile capture + a login-blocking gate. This change reverses the blocking/bundling shape.

**Decision: amend, not retire.** Following the `permission-gate` precedent (amended in place rather than deleted when its underlying behavior partially survives), this capability is **not removed outright** — the "default ADMIN auto-seed" behavior still exists, only its trigger point changes (unconditional at process start, not gated behind a first-run flow). The two requirements that described *blocking* and *bundled profile capture* are retired: blocking moves to `login-gate` (now `License → Login`, no `Onboarding` step), and profile capture becomes a standalone, optional, post-login action owned entirely by `business-profile`.

## Purpose

Originally: *"A blocking, one-time flow that runs after a successful license check and before any POS or login screen is reachable. It auto-seeds a default ADMIN account ... and captures the single business profile."* **That statement is reversed.** There is no blocking flow. The default ADMIN is seeded unconditionally at process start (`PosApplication.onCreate()`), independent of license status, business-profile state, or any prior step. This capability's remaining scope is **only** the ADMIN auto-seed and its changeability — not onboarding, not profile capture, not gating.

## MODIFIED Requirements

### Requirement: Default ADMIN Is Auto-Seeded Unconditionally At Process Start

The system MUST auto-seed exactly one ADMIN user with the default credential `admin` / `admin123` on every process start, unconditionally — independent of license status, business-profile existence, or any prior onboarding step. Seeding MUST remain idempotent (no duplicate ADMIN row on repeated starts).
(Previously: seeding was scoped to "the first app launch after a valid license" as part of a blocking onboarding flow.)

#### Scenario: A fresh install seeds the default admin at process start

- GIVEN a fresh install with no existing users
- WHEN the app process starts for the first time
- THEN an ADMIN user with username `admin` and PIN `admin123` exists in the credential store afterward, with no onboarding step involved

#### Scenario: Seeding happens exactly once across restarts

- GIVEN the default ADMIN has already been seeded
- WHEN the app process starts again
- THEN no duplicate ADMIN row is created and no re-seed occurs

### Requirement: The Seeded Credential Must Be Changeable

The system MUST provide an ADMIN credential-change flow that allows the seeded `admin` / `admin123` credential to be changed at any time. It MUST NOT be a permanent, unchangeable credential.
(Previously: identical requirement, framed as following "onboarding" rather than following unconditional startup seeding — behavior unchanged, only the preceding context.)

#### Scenario: The default admin changes their seeded PIN

- GIVEN the default ADMIN is logged in with the seeded PIN `admin123`
- WHEN they use the credential-change flow to set a new PIN
- THEN the new PIN is persisted as their credential

#### Scenario: The old default PIN no longer authenticates after a change

- GIVEN the default ADMIN has changed their PIN away from `admin123`
- WHEN a login attempt is made with username `admin` and PIN `admin123`
- THEN authentication MUST fail

## REMOVED Requirements

### Requirement: Business Profile Is Captured Once As Part Of The Same Flow

(Reason: business-profile capture is no longer bundled with ADMIN seeding or any first-run flow. It becomes a standalone, optional, always-editable ADMIN action — see `business-profile`'s amended requirements.)
(Migration: `business-profile`'s "Optional And Editable At Any Time" requirement replaces this.)

### Requirement: Onboarding Blocks Access Until Complete

(Reason: the blocking, pre-login gate is retired entirely. Login is always reachable once the license check passes, regardless of ADMIN-seeding or business-profile state.)
(Migration: `login-gate`'s amended "Gate Ordering Is License → Login" requirement replaces this.)
