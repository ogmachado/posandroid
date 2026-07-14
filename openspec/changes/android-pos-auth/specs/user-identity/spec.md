# User Identity Specification

## Purpose

A Room-backed multi-user identity model: named users, each with a role (`ADMIN` | `CASHIER`) and an individually hashed PIN credential. `UserDao` persists users; `AuthRepository` owns credential creation/verification and in-memory session state. This model **replaces** the pre-existing singleton manager-PIN primitive (`permission/PinRepository`) — it is not a second, parallel credential store (see the `permission-gate` amendment).

## Requirements

### Requirement: Users Are Named, Roled, and Individually Credentialed

The system MUST store each user as a distinct row with a unique identifier, a role of exactly `ADMIN` or `CASHIER`, and its own hashed PIN credential — never a credential shared across users.

#### Scenario: Two users have independent credentials
- GIVEN two users exist, one ADMIN and one CASHIER
- WHEN each user's PIN is checked
- THEN each is verified only against its own stored hash, never the other's

#### Scenario: Every user has exactly one role
- GIVEN any user row in the store
- WHEN its role is read
- THEN the value is exactly `ADMIN` or `CASHIER` — no third role and no null role exist

### Requirement: PIN Credentials Are Hashed At Rest

The system MUST hash every PIN credential at rest, reusing the existing `PinHasher` hashing routine, and MUST NOT store any PIN in plaintext.

#### Scenario: A newly created user's PIN is stored hashed
- GIVEN an ADMIN creates a new user with a chosen PIN
- WHEN the user row is persisted
- THEN the stored credential is a hash produced by `PinHasher`, not the raw PIN value

### Requirement: Credential Verification Is Per-User

The system MUST verify a submitted PIN against the specific user's own stored hash, not against a single global secret.

#### Scenario: A correct PIN verifies only for its owner
- GIVEN user A and user B each have their own PIN
- WHEN user A's correct PIN is submitted for verification against user B
- THEN verification MUST fail

#### Scenario: A correct PIN verifies for its owner
- GIVEN user A has a known PIN
- WHEN that PIN is submitted for verification against user A
- THEN verification MUST succeed

### Requirement: Session Identity Is Held In-Memory Only

The system MUST hold the authenticated session's user identity and role in memory only for the current app run, and MUST NOT persist a "logged in forever" flag — mirroring how license status is re-evaluated on each resume rather than cached indefinitely.

#### Scenario: Session identity is available during a live app run
- GIVEN a user has successfully authenticated
- WHEN downstream gates (role-based navigation, permission-gate) query the session
- THEN the authenticated user's identity and role are available without re-querying the database

#### Scenario: Session identity does not survive a process restart
- GIVEN a user has successfully authenticated
- WHEN the app process is killed and relaunched
- THEN no persisted "still logged in" state exists — the login gate is reached again

### Requirement: Exactly One Unified Credential Store

The system MUST NOT maintain a second, parallel PIN store alongside this per-user credential store. The pre-existing standalone singleton manager-PIN primitive is retired and absorbed into this model (see `permission-gate` amendment for the gate-level consequences).

#### Scenario: No parallel singleton credential remains reachable
- GIVEN the per-user credential store is in place
- WHEN any code path needs to verify a PIN for a gated action or login
- THEN it reads from the unified per-user credential store — no separate EncryptedSharedPreferences singleton PIN is consulted

## Design-Level Open Questions (for sdd-design)

- Exact `UserEntity` schema (column names/types, whether the identifier is a username string or a display name) is not fixed here.
- The precise migration mechanics for retiring `PinRepository`'s existing EncryptedSharedPreferences singleton (e.g., one-time import of its value into a seeded ADMIN row vs. discarding it entirely in favor of the fresh auto-seed) are a design-level decision.
- Password/PIN length and character-set rules are not specified here.
