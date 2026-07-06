# Payment Method Catalog Specification

## Purpose

A small seeded catalog distinguishing payment methods that affect the physical cash drawer (CASH) from those that do not (TRANSFER), driving the `cash-session` expected-balance math. Ported as-is from the reference backend's `sales` module payment-method catalog.

## Requirements

### Requirement: Seeded Payment Methods

The system MUST seed exactly two payment methods on first run: `CASH` with `affectsCashBalance = true`, and `TRANSFER` with `affectsCashBalance = false`.

#### Scenario: Fresh install has both seeded methods

- GIVEN a freshly installed app with no prior data
- WHEN the payment-method catalog is queried
- THEN it contains `CASH` (`affectsCashBalance = true`) and `TRANSFER` (`affectsCashBalance = false`)

### Requirement: Cash-Sales Aggregation Filters by affectsCashBalance

The system MUST include only orders whose payment method has `affectsCashBalance = true` when computing the cash-sales contribution to a cash session's `expectedBalance`.

#### Scenario: Mixed-payment session aggregates only cash-affecting orders

- GIVEN an open session has one `CASH` order of total 30 and one `TRANSFER` order of total 100
- WHEN the cash-sales total for that session is computed
- THEN the result is 30, not 130

### Requirement: A Sale Always Resolves to a Valid Payment Method

The system MUST guarantee every order has a non-null payment method: either the one explicitly chosen, or `CASH` when none is specified (see `sales-order` spec).

#### Scenario: CASH is always resolvable

- GIVEN the seeded catalog has not been altered
- WHEN any order omits a payment method
- THEN the order resolves to `CASH`, which MUST exist in the catalog

## Design-Level Open Questions (for sdd-design)

- The proposal and project docs do not request admin CRUD over payment methods for this slice (unlike the reference backend's `/admin/payment-methods`); `sdd-design` should confirm whether this catalog is read-only/seed-only in Slice A or editable behind the manager-PIN gate.
