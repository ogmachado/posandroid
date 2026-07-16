# Login Gate Specification (Amended by `android-pos-auth-login-first`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-auth/specs/login-gate/spec.md`. It reverses the `License → Onboarding → Login` gate ordering introduced there (Decision E of `android-pos-auth`'s design). The `Onboarding` step is removed from the gate entirely: `AuthGateState` becomes exactly `Loading | Login | Authenticated`. Login is now always the first screen reachable after a valid license check. The gate's authentication mechanics (user + PIN matching) are unchanged and retained below only where their surrounding text referenced onboarding.

## MODIFIED Requirements

### Requirement: Gate Sits Inside AppRoot, After License, Before PosNavHost

The system MUST decide, inside `AppRoot()`, between the login composable and `PosNavHost()` using a plain boolean/state condition on session authentication — not a navigation graph or back-stack. No onboarding step precedes the login composable.
(Previously: "Gate Sits Inside AppRoot, After License And Onboarding, Before PosNavHost" — required a completed first-run onboarding step before login was reachable.)

#### Scenario: Unauthenticated session shows the login screen, not the POS shell

- GIVEN a valid license, with no authenticated session
- WHEN `AppRoot()` composes its content
- THEN the login screen is shown, and `PosNavHost()` is not reachable

#### Scenario: Authenticated session shows the POS shell

- GIVEN a user has successfully authenticated this app run
- WHEN `AppRoot()` composes its content
- THEN `PosNavHost()` is shown

### Requirement: Gate Ordering Is License → Login

The system MUST only present the login gate after a successful license check; an unlicensed device never reaches the login screen. No onboarding or business-profile step sits between the license check and the login screen — the default ADMIN is already seeded unconditionally at process start (see `first-run-onboarding`'s amended requirement), so login is always reachable the moment the license check passes.
(Previously: "Gate Ordering Is License → Onboarding → Login" — required a completed first-run onboarding step, gated on business-profile existence, before login was reachable.)

#### Scenario: An unlicensed device never reaches login

- GIVEN license status is not `VALID`/`IN_GRACE_PERIOD`
- WHEN the app composes its content
- THEN the activation screen is shown — the login screen is unreachable

#### Scenario: A freshly licensed device reaches login directly, with no intermediate step

- GIVEN a valid license on a fresh install (no prior users beyond the unconditionally seeded default ADMIN)
- WHEN the app composes its content
- THEN the login screen is shown immediately — no onboarding or business-profile step is reachable first

## Unchanged (carried forward from the base spec, not restated here)

- "Correct User + PIN Authenticates" — authentication mechanics are untouched by this change.
- The Design-Level Open Questions from the base spec remain open and unaffected.
