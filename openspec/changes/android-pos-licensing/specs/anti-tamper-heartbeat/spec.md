# Anti-Tamper Heartbeat Specification

## Purpose

Detection of wall-clock rollback (a common offline-license bypass: setting the device clock backward to appear "not yet expired") via a foreground-triggered heartbeat, and the resulting sticky `COMPROMISED` state. Adapted from the reference backend's `TimeAntiTamperService`, replacing the `@Scheduled` server task with a foreground-lifecycle trigger (no `WorkManager`, no background scheduling).

## Requirements

### Requirement: Heartbeat Is Foreground-Triggered

The system MUST record a heartbeat timestamp whenever the app returns to the foreground (app start or resume from background), and MUST NOT rely on any background-scheduled task to do so.

#### Scenario: Resuming the app records a heartbeat
- GIVEN the app was previously backgrounded with a recorded heartbeat timestamp
- WHEN the operator brings the app back to the foreground
- THEN a new heartbeat timestamp is recorded for comparison against the previous one

### Requirement: Clock-Rollback Detection

The system MUST compare the current device wall-clock time against the last recorded heartbeat timestamp (persisted in Room) and MUST flag a rollback when the current time is earlier than the last heartbeat by more than a configurable tolerance.

#### Scenario: Clock moved backward beyond tolerance is detected
- GIVEN the last recorded heartbeat was at time `T`
- WHEN the app resumes and the device clock now reads a time earlier than `T` minus tolerance
- THEN a clock-rollback is detected

#### Scenario: Small forward or backward drift within tolerance is not flagged
- GIVEN the last recorded heartbeat was at time `T`
- WHEN the app resumes and the device clock reads a time within the configured tolerance of `T` (forward or slightly backward)
- THEN no rollback is flagged

### Requirement: Rollback Marks the License COMPROMISED

The system MUST set the license status to `COMPROMISED` immediately upon detecting a clock rollback, overriding whatever status the expiration-based evaluation (`license-verification`) would otherwise produce.

#### Scenario: Detected rollback overrides a currently VALID license
- GIVEN the license would otherwise evaluate as `VALID` based on its expiration date
- WHEN a clock rollback is detected on foreground resume
- THEN the license status becomes `COMPROMISED`, and the enforcement gate blocks the app

### Requirement: COMPROMISED Status Is Sticky

The system MUST persist the `COMPROMISED` status durably (Room) and MUST NOT clear it automatically — not on subsequent app restarts, not when the clock is corrected, and not with the passage of time. The only way out is installing a new, validly-signed license.

#### Scenario: Correcting the clock does not clear COMPROMISED
- GIVEN the license status is `COMPROMISED` due to a detected rollback
- WHEN the operator corrects the device clock and restarts the app
- THEN the status remains `COMPROMISED`

#### Scenario: Installing a new valid license clears COMPROMISED
- GIVEN the license status is `COMPROMISED`
- WHEN the operator successfully installs a new, validly-signed, correctly-bound license (per `license-activation`)
- THEN the status is re-evaluated fresh from the new license and is no longer `COMPROMISED` unless a new rollback is detected afterward

## Design-Level Open Questions (for sdd-design)

- The exact clock-drift tolerance value (seconds) is not fixed by this spec; sdd-design MUST choose and document a configurable value, mirroring the reference backend's `IDOS_LICENSE_TIME_TOLERANCE_SECONDS` pattern.
- The precise Room schema for the heartbeat/status table (columns, migration) is an implementation detail for sdd-design, not fixed here.
