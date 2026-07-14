# User Management Specification

## Purpose

ADMIN-only creation and visibility of additional user accounts (`CASHIER` or `ADMIN`), matching the desktop reference's "only ADMIN manages users" convention. There is no cashier self-registration path.

## Requirements

### Requirement: Only ADMIN Can Create Accounts

The system MUST allow only a session authenticated with the `ADMIN` role to create a new user account (`CASHIER` or `ADMIN`).

#### Scenario: An ADMIN creates a new CASHIER account
- GIVEN a session authenticated as `ADMIN`
- WHEN that ADMIN submits a new account with role `CASHIER`, a unique identifier, and a PIN
- THEN the new user is persisted with role `CASHIER` and its own hashed credential

#### Scenario: An ADMIN creates a new ADMIN account
- GIVEN a session authenticated as `ADMIN`
- WHEN that ADMIN submits a new account with role `ADMIN`, a unique identifier, and a PIN
- THEN the new user is persisted with role `ADMIN` and its own hashed credential

#### Scenario: A CASHIER has no account-creation path
- GIVEN a session authenticated as `CASHIER`
- WHEN that session's available screens/actions are enumerated
- THEN no account-creation action is reachable

### Requirement: New Accounts Require A Unique Identifier And An Individually Set PIN

The system MUST reject creating a user whose identifier collides with an existing user, and MUST require the PIN to be set explicitly at creation time — never left blank or defaulted.

#### Scenario: Duplicate identifier is rejected
- GIVEN a user with identifier `maria` already exists
- WHEN an ADMIN attempts to create another user with identifier `maria`
- THEN account creation MUST be rejected

#### Scenario: A PIN must be supplied at creation
- GIVEN an ADMIN is creating a new account
- WHEN no PIN is supplied
- THEN account creation MUST be rejected — no account is created with a blank or default PIN

### Requirement: An ADMIN Can View The List Of Existing Accounts

The system MUST let an ADMIN view the list of existing users (identifier and role) it manages.

#### Scenario: An ADMIN views existing accounts
- GIVEN two or more users exist
- WHEN an ADMIN opens the user-management screen
- THEN every existing user's identifier and role are visible in the list

## Design-Level Open Questions (for sdd-design)

- Whether accounts can be edited (role change, deactivation, deletion) after creation, beyond the PIN-change flow already required by `first-run-onboarding`, is not committed here — the proposal scopes this capability to creation + visibility; edit/deactivate is a design-level extension if pursued, not a fixed requirement.
- Exact uniqueness rules for the identifier (case sensitivity, allowed characters) are not specified here.
