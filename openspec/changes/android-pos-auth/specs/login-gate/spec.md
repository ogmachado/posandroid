# Login Gate Specification

## Purpose

The post-license, pre-POS authentication gate. Following the established `EnforcementGate` boolean-composable-swap idiom, it slots **inside** `AppRoot()`, before `PosNavHost()` — it is not a new navigation route. An operator authenticates by identifying a user and entering that user's PIN before the POS shell becomes reachable.

## Requirements

### Requirement: Gate Sits Inside AppRoot, After License And Onboarding, Before PosNavHost

The system MUST decide, inside `AppRoot()`, between the login composable and `PosNavHost()` using a plain boolean/state condition on session authentication — not a navigation graph or back-stack.

#### Scenario: Unauthenticated session shows the login screen, not the POS shell
- GIVEN a valid license and completed first-run onboarding, with no authenticated session
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

### Requirement: Gate Ordering Is License → Onboarding → Login

The system MUST only present the login gate after a successful license check and completed first-run onboarding; an unlicensed or not-yet-onboarded state never reaches the login screen.

#### Scenario: An unlicensed device never reaches login
- GIVEN license status is not `VALID`/`IN_GRACE_PERIOD`
- WHEN the app composes its content
- THEN the activation screen is shown — the login screen is unreachable

#### Scenario: A licensed but not-yet-onboarded device shows onboarding, not login
- GIVEN a valid license and onboarding not yet complete
- WHEN the app composes its content
- THEN the onboarding flow is shown — the login screen is unreachable until onboarding completes

## Design-Level Open Questions (for sdd-design)

- Exact user-identification UX (typed username vs. a selectable list of existing users) is not fixed here.
- Whether a logout/switch-user affordance exists once authenticated is not committed by this proposal — no such capability appears in scope; if design finds a compelling reason to add one, it is a design-level addition, not implied here.
- Retry limit / lockout behavior on repeated incorrect PIN attempts is not specified here, consistent with the existing `permission-gate` spec's own silence on this point.
