# Inventory Ledger Specification

## Purpose

Stock is a ledger of movements (IN/OUT/ADJUST), not a directly mutable counter. A single owning primitive mutates stock; every other code path (including sales) MUST go through it. Ported from the reference backend's `inventory` module, with the pessimistic-locking/deadlock-avoidance concerns of that module dropped — see the design-level note on concurrency below.

## Requirements

### Requirement: Single-Owner Stock Mutation

The system MUST expose exactly one stock-mutation primitive. No other component MAY write to a product's stock value directly; every stock change MUST be recorded as an `InventoryMovement` (type `IN`, `OUT`, or `ADJUST`).

#### Scenario: Sale mutates stock only through the ledger primitive

- GIVEN a product has stock of 10
- WHEN a sale line for quantity 3 is created
- THEN stock decreases to 7 exclusively via an `OUT` movement recorded against that product
- AND no code path other than the ledger primitive touches the stock value

### Requirement: IN Movement Increases Stock

The system MUST increase a product's stock by the movement quantity when an `IN` movement is recorded, creating the inventory row with stock 0 if one does not yet exist.

#### Scenario: Receive stock for a product with no prior inventory row

- GIVEN a product has never had an inventory row
- WHEN an `IN` movement of quantity 20 is recorded for it
- THEN the product's stock becomes 20

### Requirement: OUT Movement Decreases Stock and MUST NOT Go Negative

The system MUST decrease stock by the movement quantity for an `OUT` movement, and MUST reject the movement if the resulting stock would be negative.

#### Scenario: OUT movement within available stock

- GIVEN a product has stock of 10
- WHEN an `OUT` movement of quantity 4 is recorded
- THEN stock becomes 6 and the movement is persisted

#### Scenario: OUT movement exceeding available stock

- GIVEN a product has stock of 3
- WHEN an `OUT` movement of quantity 5 is requested
- THEN the movement MUST be rejected with an insufficient-stock error
- AND stock MUST remain unchanged at 3
- AND no movement row is persisted

#### Scenario: OUT movement against a product with no inventory row

- GIVEN a product has never had an inventory row (implicit stock of 0)
- WHEN an `OUT` movement of quantity 1 is requested
- THEN the movement MUST be rejected with an insufficient-stock error

### Requirement: ADJUST Movement Sets Absolute Stock

The system MUST set stock to the movement's exact quantity for an `ADJUST` movement (not additive), creating the inventory row if it does not exist. The quantity MUST NOT be negative.

#### Scenario: Adjust stock to a new absolute value

- GIVEN a product has stock of 15
- WHEN an `ADJUST` movement sets quantity to 8
- THEN stock becomes exactly 8, regardless of the prior value

#### Scenario: Adjust with a negative quantity

- GIVEN any product
- WHEN an `ADJUST` movement is requested with quantity -1
- THEN the movement MUST be rejected as invalid input

### Requirement: Minimum Stock Tracking

The system MUST persist a `minimumStock` field per product, independent of the current stock value, settable without requiring a movement.

#### Scenario: Set minimum stock

- GIVEN a product with stock of 8 and no minimum stock configured
- WHEN minimum stock is set to 5
- THEN the product's minimum-stock threshold is 5 and its current stock remains 8

## Design-Level Open Questions (for sdd-design)

- Single-writer discipline replaces the reference backend's `SELECT ... FOR UPDATE` pessimistic lock; per the proposal/exploration, no concurrent writer exists on one device, so this spec does NOT require a locking mechanism — `sdd-design` should document the single-writer guarantee (e.g., serialized Room DAO access) rather than re-introduce locking.
