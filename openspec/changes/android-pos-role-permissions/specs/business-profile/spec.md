# Business Profile Specification (Amended by `android-pos-role-permissions`)

## Amendment Note

This is a **delta/amendment** to `openspec/changes/android-pos-auth-login-first/specs/business-profile/spec.md`. That spec's "ADMIN-only" guarantee was UI-reachability-only: a CASHIER session sees no header action, but the underlying save write path performed no caller-role check of its own. This amendment strengthens the requirement so the write path (`BusinessProfileRepository.save()` or its ViewModel) itself rejects a non-ADMIN caller — the same UI-hiding-only weakness pattern fixed for `user-management` in this same change. The single-row upsert behavior and the separate "Captured Profile Is Readable After Save" requirement are **retained unchanged** and not restated here.

## MODIFIED Requirements

### Requirement: The Profile Is Optional And Editable At Any Time, With Both UI Reachability And The Write Path Restricted To ADMIN

The system MUST make business-profile setup reachable at any time, after login, via an ADMIN-only header action in `AppRoot()` — not gated behind, or bundled with, any first-run flow. The profile MUST be optional: an ADMIN MAY operate the full POS without ever setting it, with no blocking gate, nag, or banner enforcing capture. Fields default to empty/nullable until an ADMIN explicitly saves values. In addition, the underlying save write path MUST itself reject a caller whose session role is not `ADMIN`, independent of whether the triggering UI action was reachable.
(Previously: "The Profile Is Optional And Editable At Any Time Via An ADMIN-Only Action" — restricted only the header action's UI visibility; the save write path performed no caller-role check of its own.)

#### Scenario: An ADMIN opens business-profile setup from the header action
- GIVEN a logged-in ADMIN viewing the POS shell
- WHEN they select the business-profile header action (next to "Manage users")
- THEN the business-profile screen opens, pre-loaded with any existing saved values

#### Scenario: An ADMIN edits and saves the business profile after login
- GIVEN a logged-in ADMIN on the business-profile screen
- WHEN they change the name, address, or phone and save
- THEN the single business-profile row is updated with the new values (via the existing upsert-on-save behavior)

#### Scenario: The POS is fully usable with no business profile ever set
- GIVEN a logged-in ADMIN and no business-profile row has ever been saved
- WHEN the ADMIN uses products, inventory, sales, or cash-session screens
- THEN none of them are blocked, nagged, or banner-interrupted by the absence of a business profile

#### Scenario: A CASHIER cannot reach business-profile setup via the header
- GIVEN a logged-in CASHIER
- WHEN the POS shell header is composed
- THEN no business-profile action is shown or reachable to that session

#### Scenario: A CASHIER-role caller invoking the save write path directly is rejected
- GIVEN a `CASHIER`-role caller, bypassing the UI entirely
- WHEN the business-profile save write path is invoked directly in a test with that caller's role
- THEN the call MUST be rejected — the business-profile row is unchanged

## Unchanged (carried forward from the base spec, not restated here)

- "The Captured Profile Is Readable After Save"
- "Exactly One Business Profile Row Exists"
- "No Multi-Store / No `storeId` Reintroduction"
