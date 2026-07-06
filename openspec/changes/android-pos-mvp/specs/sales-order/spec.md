# Sales Order Specification

## Purpose

SALE-only order creation for this slice: an order and its lines are created atomically together with the corresponding stock decrement. Ported from the reference backend's `sales` module, dropping store scoping and the RETURN/VOID order types (both explicitly deferred to a later change).

## Requirements

### Requirement: Atomic Order Creation

The system MUST create an order and all of its lines, together with one `OUT` inventory movement per line, as a single atomic unit. If any part fails (e.g., insufficient stock on any line), the entire order creation MUST be rolled back — no partial order, no partial stock decrement.

#### Scenario: Successful order with two lines

- GIVEN product A has stock 10 and product B has stock 5
- WHEN an order is created with line A×2 and line B×1
- THEN the order and both lines are persisted
- AND stock for A becomes 8 and stock for B becomes 4
- AND exactly two `OUT` movements are recorded, one per line

#### Scenario: One line fails stock validation, whole order is rolled back

- GIVEN product A has stock 10 and product B has stock 1
- WHEN an order is created with line A×2 and line B×5
- THEN the entire order creation MUST fail with an insufficient-stock error
- AND no order, no lines, and no movements are persisted
- AND stock for both A and B remains unchanged

### Requirement: Per-Day Sequential Order Number

The system MUST assign each order a sequential number scoped to the calendar day it is created on (no store scoping, since only one implicit store exists). The sequence MUST restart at 1 for the next day.

#### Scenario: First order of the day

- GIVEN no order has been created yet today
- WHEN an order is created
- THEN its order number is 1

#### Scenario: Second order of the same day

- GIVEN one order was already created today with order number 1
- WHEN another order is created the same day
- THEN its order number is 2

#### Scenario: First order of a new day

- GIVEN the last order created yesterday had order number 7
- WHEN an order is created today
- THEN its order number is 1, not 8

### Requirement: Payment Method Resolution Defaults to CASH

The system MUST resolve the order's payment method from an optional request field; when the field is absent, it MUST default to the seeded `CASH` payment method.

#### Scenario: Order created without specifying a payment method

- GIVEN the seeded `CASH` payment method exists
- WHEN an order is created with no payment method specified
- THEN the order's payment method is `CASH`

#### Scenario: Order created specifying an existing payment method

- GIVEN the seeded `TRANSFER` payment method exists
- WHEN an order is created specifying `TRANSFER`
- THEN the order's payment method is `TRANSFER`

#### Scenario: Order created specifying a non-existent payment method

- GIVEN no payment method exists with id `999`
- WHEN an order is created specifying payment method id `999`
- THEN the creation MUST be rejected as a not-found error

### Requirement: Order Requires an Open Cash Session

The system MUST reject order creation when no cash session is currently open on the device.

#### Scenario: Order attempted with no open session

- GIVEN no cash session is open
- WHEN an order is created
- THEN the creation MUST be rejected with a no-open-session error

#### Scenario: Order attempted with an open session

- GIVEN a cash session is open
- WHEN an order is created
- THEN the order is linked to that open session

## Non-Goals (explicit — do not implement in this change)

- `RETURN` order type and remaining-returnable-quantity validation — deferred.
- `VOID` and compensating reversal movements — deferred.
