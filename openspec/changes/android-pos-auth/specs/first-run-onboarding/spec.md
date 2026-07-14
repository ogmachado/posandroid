# First-Run Onboarding Specification

## Purpose

A blocking, one-time flow that runs after a successful license check and before any POS or login screen is reachable. It auto-seeds a default ADMIN account (`admin` / `admin123`, mirroring the desktop reference) and captures the single business profile (name, address, phone). The seeded credential is not a permanent known credential — an ADMIN credential-change flow MUST exist to replace it afterward.

## Requirements

### Requirement: Default ADMIN Is Auto-Seeded On First Launch

The system MUST auto-seed exactly one ADMIN user with the default credential `admin` / `admin123` on the first app launch after a valid license, without requiring the operator to create it manually.

#### Scenario: A fresh install seeds the default admin
- GIVEN a fresh install with a valid license and no existing users
- WHEN the app is launched for the first time
- THEN an ADMIN user with username `admin` and PIN `admin123` exists in the credential store afterward

#### Scenario: Seeding happens exactly once
- GIVEN the default ADMIN has already been seeded
- WHEN the app is launched again (or the process restarts)
- THEN no duplicate ADMIN row is created and no re-seed occurs

### Requirement: Business Profile Is Captured Once As Part Of The Same Flow

The system MUST present a one-time capture of the business profile (name, address, phone) as part of the same first-run flow, persisted as the single business-profile row (see `business-profile`).

#### Scenario: First-run flow captures the business profile
- GIVEN a fresh install completing first-run onboarding
- WHEN the operator supplies business name, address, and phone
- THEN exactly one business-profile row is persisted with those values

#### Scenario: Business profile is not re-prompted after first capture
- GIVEN the business profile has already been captured once
- WHEN the app is launched again
- THEN onboarding does not re-prompt for the business profile

### Requirement: Onboarding Blocks Access Until Complete

The system MUST block reaching the login gate (and therefore the POS shell) until both the default-ADMIN seeding and the business-profile capture have completed, following the established boolean-composable-swap idiom (not a navigation route).

#### Scenario: Incomplete onboarding blocks the login gate
- GIVEN the business-profile capture has not yet been confirmed
- WHEN the app composes its content after a valid license check
- THEN the onboarding composable is shown, not the login screen

#### Scenario: Completed onboarding reveals the login gate
- GIVEN onboarding (seeding + business-profile capture) has completed
- WHEN the app is launched again
- THEN the login screen is reached directly, with no onboarding step in between

### Requirement: The Seeded Credential Must Be Changeable

The system MUST provide an ADMIN credential-change flow that allows the seeded `admin` / `admin123` credential to be changed after onboarding. It MUST NOT be a permanent, unchangeable credential.

#### Scenario: The default admin changes their seeded PIN
- GIVEN the default ADMIN is logged in with the seeded PIN `admin123`
- WHEN they use the credential-change flow to set a new PIN
- THEN the new PIN is persisted as their credential

#### Scenario: The old default PIN no longer authenticates after a change
- GIVEN the default ADMIN has changed their PIN away from `admin123`
- WHEN a login attempt is made with username `admin` and PIN `admin123`
- THEN authentication MUST fail

## Design-Level Open Questions (for sdd-design)

- The exact seeding trigger point (e.g., `MIGRATION_2_3`'s post-migration callback vs. a repository-level check on first DB access) is not fixed here.
- Whether the credential-change flow is surfaced as a mandatory nag (until the default is changed) or an optional settings entry is a design-level decision — the proposal does not mandate enforcement, only availability.
- Recovery path for a lost sole-ADMIN PIN (e.g., reinstall/wipe) is out of scope for this capability; at minimum, design should document the consequence.
