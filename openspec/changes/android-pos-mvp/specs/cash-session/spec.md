# Cash Session Specification

## Purpose

A single open shift per device (no per-cashier dimension — exactly one operator exists). Tracks opening balance, manual cash movements, and reconciles against cash sales at close time. Ported from the reference backend's `cash-register` module, dropping the cash-register catalog and the cashier-username scoping (both meaningless with one device and one operator).

## Requirements

### Requirement: Single Open Session Per Device

The system MUST allow at most one open cash session at any time. Opening a new session while one is already open MUST be rejected.

#### Scenario: Open the first session

- GIVEN no cash session is currently open
- WHEN a session is opened with an opening balance
- THEN the session is created with status OPEN and that opening balance

#### Scenario: Attempt to open a second session while one is open

- GIVEN a cash session is already open
- WHEN opening a new session is attempted
- THEN the request MUST be rejected with an already-open error
- AND the existing open session MUST remain unaffected

### Requirement: Manual Cash Movements Only on an Open Session

The system MUST allow recording DEPOSIT and WITHDRAWAL movements only against the currently open session, each carrying a type and an amount.

#### Scenario: Deposit on an open session

- GIVEN a session is open
- WHEN a DEPOSIT movement of amount 50 is recorded
- THEN the movement is persisted against that session

#### Scenario: Movement attempted with no open session

- GIVEN no session is open
- WHEN a DEPOSIT movement is attempted
- THEN the request MUST be rejected with a no-open-session error

#### Scenario: Movement attempted against a closed session

- GIVEN a session was opened and then closed
- WHEN a WITHDRAWAL movement is attempted against that closed session
- THEN the request MUST be rejected with a session-not-open error

### Requirement: Expected Balance Formula on Close

The system MUST compute, at close time:

`expectedBalance = openingBalance + Σ DEPOSIT amounts − Σ WITHDRAWAL amounts + Σ cash-affecting sale totals for that session`

where "cash-affecting sale totals" means the sum of totals of orders linked to the session whose payment method has `affectsCashBalance = true`.

#### Scenario: Close with deposits, a withdrawal, and one cash sale

- GIVEN a session opened with balance 100
- AND a DEPOSIT of 20 and a WITHDRAWAL of 10 were recorded
- AND one order of total 30 was created during the session with payment method `CASH` (`affectsCashBalance = true`)
- WHEN the session is closed
- THEN `expectedBalance = 100 + 20 − 10 + 30 = 140`

#### Scenario: Close with a non-cash-affecting sale excluded from the formula

- GIVEN a session opened with balance 100 and no movements
- AND one order of total 30 was created with payment method `TRANSFER` (`affectsCashBalance = false`)
- WHEN the session is closed
- THEN `expectedBalance = 100` (the transfer sale does not contribute)

#### Scenario: Close with no sales and no movements

- GIVEN a session opened with balance 50 and no movements or sales
- WHEN the session is closed
- THEN `expectedBalance = 50`

### Requirement: Session Close Is Terminal

The system MUST mark a closed session as CLOSED and MUST reject a second close attempt on the same session.

#### Scenario: Close an already-closed session

- GIVEN a session was already closed
- WHEN closing it again is attempted
- THEN the request MUST be rejected with a session-not-open error
