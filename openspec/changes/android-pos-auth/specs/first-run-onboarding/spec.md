# First-Run Onboarding Specification

## Amendment History

**Amendment (2026-07-16, `android-pos-auth-login-first`)**: Reversed the blocking first-run flow shape. ADMIN seeding is now unconditional at process start (not gated behind a first-run flow). Business-profile capture and onboarding blocking are retired from this spec (moved to `business-profile` and `login-gate` respectively). Retained: default ADMIN auto-seed and its changeability.

## Purpose

The system unconditionally auto-seeds a default ADMIN account (`admin` / `admin123`) at process start, independent of license status, business-profile state, or any prior flow. There is no blocking first-run onboarding flow. The seeded credential is changeable — an ADMIN credential-change flow MUST exist to allow replacing it afterward.

## Requirements

### Requirement: Default ADMIN Is Auto-Seeded Unconditionally At Process Start

The system MUST auto-seed exactly one ADMIN user with the default credential `admin` / `admin123` on every process start, unconditionally — independent of license status, business-profile existence, or any prior onboarding step. Seeding MUST remain idempotent (no duplicate ADMIN row on repeated starts).

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

#### Scenario: The default admin changes their seeded PIN
- GIVEN the default ADMIN is logged in with the seeded PIN `admin123`
- WHEN they use the credential-change flow to set a new PIN
- THEN the new PIN is persisted as their credential

#### Scenario: The old default PIN no longer authenticates after a change
- GIVEN the default ADMIN has changed their PIN away from `admin123`
- WHEN a login attempt is made with username `admin` and PIN `admin123`
- THEN authentication MUST fail

## Design-Level Open Questions (for sdd-design)

- The exact seeding trigger point (e.g., `PosApplication.onCreate()` vs. a repository-level check on first DB access) is not fixed here.
- Whether the credential-change flow is surfaced as a mandatory nag (until the default is changed) or an optional settings entry is a design-level decision — the proposal does not mandate enforcement, only availability.
- Recovery path for a lost sole-ADMIN PIN (e.g., reinstall/wipe) is out of scope for this capability; at minimum, design should document the consequence.
