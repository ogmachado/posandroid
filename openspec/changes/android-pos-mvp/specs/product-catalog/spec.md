# Product Catalog Specification

## Purpose

Local catalog of sellable products, categorized and measured in a unit of measure, identifiable by an internal code and an optional barcode for camera-scan lookup at the point of sale. Ported from the reference backend's `products` module, with no store scoping (single implicit store).

## Requirements

### Requirement: Product Creation

The system MUST allow creating a product with a name, a unique code, a price, a cost price, a required unit-of-measure reference, and an optional category reference.

#### Scenario: Create a product with valid data

- GIVEN a unit of measure and a category exist
- WHEN a product is created with a unique code, no barcode, referencing that unit of measure and category
- THEN the product is persisted with zero stock in the inventory ledger
- AND no inventory movement is created as part of product creation

#### Scenario: Create a product with a duplicate code

- GIVEN a product with code `"SKU-001"` already exists
- WHEN a new product is created with code `"SKU-001"`
- THEN the creation MUST be rejected with a code-uniqueness violation
- AND no product is persisted

#### Scenario: Create a product referencing a missing unit of measure

- GIVEN no unit of measure exists with id `99`
- WHEN a product is created referencing unit-of-measure id `99`
- THEN the creation MUST be rejected as a not-found error

### Requirement: Barcode Uniqueness Is Optional-If-Present

The system MUST treat `barcode` as optional. A blank or whitespace-only barcode MUST be normalized to absent (no value), and a present barcode MUST be unique across all products.

#### Scenario: Create a product with no barcode

- GIVEN no barcode is supplied
- WHEN the product is created
- THEN the product is persisted with an absent barcode
- AND it does not block a future product from also having an absent barcode

#### Scenario: Create a product with a blank barcode

- GIVEN a barcode value of `"   "` (whitespace only) is supplied
- WHEN the product is created
- THEN the barcode MUST be stored as absent, not as the literal whitespace string

#### Scenario: Create a product with a duplicate barcode

- GIVEN a product exists with barcode `"5901234123457"`
- WHEN a new product is created with barcode `"5901234123457"`
- THEN the creation MUST be rejected with a barcode-uniqueness violation

#### Scenario: Update a product's barcode to one already used by another product

- GIVEN product A has barcode `"111"` and product B has barcode `"222"`
- WHEN product B is updated to barcode `"111"`
- THEN the update MUST be rejected with a barcode-uniqueness violation
- AND updating a product to keep its own current barcode MUST succeed

### Requirement: Barcode Lookup via Camera Scan

The system MUST resolve a single product by exact barcode match to support the camera barcode-scan flow at the point of sale.

#### Scenario: Scan resolves an existing product

- GIVEN a product exists with barcode `"5901234123457"`
- WHEN the camera scan decodes `"5901234123457"`
- THEN the matching product is returned

#### Scenario: Scan finds no match

- GIVEN no product has barcode `"000000000000"`
- WHEN the camera scan decodes `"000000000000"`
- THEN the lookup MUST report not-found, and the UI MUST allow falling back to manual product search

### Requirement: Product Update Preserves Code Uniqueness

The system MUST re-validate code uniqueness on update against every other product (excluding the product being updated).

#### Scenario: Update keeping the same code

- GIVEN a product currently has code `"SKU-001"`
- WHEN it is updated with code `"SKU-001"` unchanged
- THEN the update MUST succeed

#### Scenario: Update to a code owned by another product

- GIVEN product A has code `"SKU-001"` and product B has code `"SKU-002"`
- WHEN product B is updated to code `"SKU-001"`
- THEN the update MUST be rejected with a code-uniqueness violation

## Design-Level Open Questions (for sdd-design)

- Whether per-product price/cost is a plain product field or still routed through an inventory-owned price override (reference backend's per-store override "disappears" per proposal — design must pick the simplest local shape).
