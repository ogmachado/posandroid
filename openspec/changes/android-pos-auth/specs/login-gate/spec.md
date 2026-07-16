# Login Gate Specification

## Amendment History

**Amendment (2026-07-16, `android-pos-auth-login-first`)**: Removed the `Onboarding` step from gate ordering. The gate now sequences directly from license to login (no intermediate onboarding): `AuthGateState` becomes exactly `Loading | Login | Authenticated`. The default ADMIN is seeded unconditionally at process start (see `first-run-onboarding`'s amended requirement), so login is always reachable immediately after the license check.

## Purpose

The post-license, pre-POS authentication gate. Following the established `EnforcementGate` boolean-composable-swap idiom, it slots **inside** `AppRoot()`, before `PosNavHost()` — it is not a new navigation route. An operator authenticates by identifying a user and entering that user's PIN before the POS shell becomes reachable. No onboarding step precedes the login screen.

## Requirements

### Requirement: Gate Sits Inside AppRoot, After License, Before PosNavHost

The system MUST decide, inside `AppRoot()`, between the login composable and `PosNavHost()` using a plain boolean/state condition on session authentication — not a navigation graph or back-stack. No onboarding step precedes the login composable.

#### Scenario: Unauthenticated session shows the login screen, not the POS shell
- GIVEN a valid license, with no authenticated session
- WHEN `AppRoot()` composes its content
- THEN the login screen is shown, and `PosNavHost()` is not reachable

#### Scenario: Authenticated session shows the POS shell
- GIVEN a user has successfully authenticated this app run
- WHEN `AppRoot()` composes its content
- THEN `PosNavHost()` is shown

### Requirement: Correct User + PIN Authenticates

The system MUST authenticate the session only when the submitted user identifier and PIN match a stored user's credential (verified per `user-identity`'s per-user verification requirement).

#### Scenario: Correct user and PIN authenticate
- GIVEN a stored user with a known PIN
- WHEN the operator selects/identifies that user and enters the correct PIN
- THEN the session becomes authenticated as that user, with that user's role

#### Scenario: Incorrect PIN blocks authentication
- GIVEN a stored user with a known PIN
- WHEN the operator identifies that user and enters an incorrect PIN
- THEN authentication MUST be rejected and the login screen remains shown

### Requirement: Gate Ordering Is License → Login

The system MUST only present the login gate after a successful license check; an unlicensed device never reaches the login screen. No onboarding or business-profile step sits between the license check and the login screen — the default ADMIN is already seeded unconditionally at process start (see `first-run-onboarding`'s amended requirement), so login is always reachable the moment the license check passes.

#### Scenario: An unlicensed device never reaches login
- GIVEN license status is not `VALID`/`IN_GRACE_PERIOD`
- WHEN the app composes its content
- THEN the activation screen is shown — the login screen is unreachable

#### Scenario: A freshly licensed device reaches login directly, with no intermediate step
- GIVEN a valid license on a fresh install (no prior users beyond the unconditionally seeded default ADMIN)
- WHEN the app composes its content
- THEN the login screen is shown immediately — no onboarding or business-profile step is reachable first

## Design-Level Open Questions (for sdd-design)

- Exact user-identification UX (typed username vs. a selectable list of existing users) is not fixed here.
- Whether a logout/switch-user affordance exists once authenticated is not committed by this proposal — no such capability appears in scope; if design finds a compelling reason to add one, it is a design-level addition, not implied here.
- Retry limit / lockout behavior on repeated incorrect PIN attempts is not specified here, consistent with the existing `permission-gate` spec's own silence on this point.
