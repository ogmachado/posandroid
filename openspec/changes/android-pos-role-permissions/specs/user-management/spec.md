# User Management Specification (Amended by `android-pos-role-permissions`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-auth/specs/user-management/spec.md`. Its existing "Only ADMIN Can Create Accounts" requirement only asserted UI-reachability — that no account-creation action is *reachable* to a CASHIER. It said nothing about what happens if the underlying write path is invoked directly. This amendment strengthens the requirement so `AuthRepository.createUser` and `AuthRepository.changePin` reject a non-ADMIN caller themselves, provable by a test that invokes them directly, bypassing the UI. All other origin requirements — unique identifier + explicit PIN at creation, ADMIN can view account list — are **retained unchanged** and not restated here.

## MODIFIED Requirements

### Requirement: Only ADMIN Can Create Accounts Or Change PINs

The system MUST allow only a session authenticated with the `ADMIN` role to create a new user account (`CASHIER` or `ADMIN`) or change an existing user's PIN. `AuthRepository.createUser` and `AuthRepository.changePin` MUST themselves reject a caller whose role is not `ADMIN`, independent of whether the UI that would normally invoke them is reachable.
(Previously: "Only ADMIN Can Create Accounts" — asserted only that no account-creation action is *reachable* to a CASHIER through the UI; the underlying write path performed no caller-role check of its own, and `changePin` was not covered by this requirement at all.)

#### Scenario: An ADMIN creates a new CASHIER account
- GIVEN a session authenticated as `ADMIN`
- WHEN that ADMIN submits a new account with role `CASHIER`, a unique identifier, and a PIN
- THEN the new user is persisted with role `CASHIER` and its own hashed credential

#### Scenario: An ADMIN creates a new ADMIN account
- GIVEN a session authenticated as `ADMIN`
- WHEN that ADMIN submits a new account with role `ADMIN`, a unique identifier, and a PIN
- THEN the new user is persisted with role `ADMIN` and its own hashed credential

#### Scenario: A CASHIER has no account-creation path in the UI
- GIVEN a session authenticated as `CASHIER`
- WHEN that session's available screens/actions are enumerated
- THEN no account-creation action is reachable

#### Scenario: A CASHIER caller invoking `createUser` directly is rejected
- GIVEN a `CASHIER`-role caller, bypassing any UI
- WHEN `AuthRepository.createUser` is invoked directly in a test with that caller's role
- THEN the call MUST be rejected — no new user row is persisted

#### Scenario: A CASHIER caller invoking `changePin` directly is rejected
- GIVEN a `CASHIER`-role caller, bypassing any UI
- WHEN `AuthRepository.changePin` is invoked directly in a test with that caller's role
- THEN the call MUST be rejected — the target user's stored credential is unchanged

## Unchanged (carried forward from the base spec, not restated here)

- "New Accounts Require A Unique Identifier And An Individually Set PIN"
- "An ADMIN Can View The List Of Existing Accounts"
