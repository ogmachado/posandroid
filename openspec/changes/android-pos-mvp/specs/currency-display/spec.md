# Currency Display Specification

## Purpose

Display-only conversion of order totals into an alternative currency below the primary-currency amount. No conversion is persisted on the order. Ported as-is from the reference backend's currency convention.

## Requirements

### Requirement: Seeded Currencies

The system MUST treat CUP as the implicit primary currency (not a selectable row) and MUST seed exactly one alternative currency, USD, active by default.

#### Scenario: Fresh install has the seeded alternative currency

- GIVEN a freshly installed app with no prior data
- WHEN the currency catalog is queried
- THEN it contains USD as an active alternative currency with an `exchangeRate`

### Requirement: Exchange Rate Convention

The system MUST store `exchangeRate` as "units of the primary currency (CUP) per 1 unit of the alternative currency (USD)," matching the reference backend's convention.

#### Scenario: Rate is stored as primary-per-alt

- GIVEN 1 USD is worth 540 CUP at the configured rate
- WHEN the USD currency row is inspected
- THEN its `exchangeRate` value is `540`, not `1/540`

### Requirement: Conversion Row Is Computed, Not Persisted

The system MUST compute the converted amount for display as `total / exchangeRate`, at render time, and MUST NOT persist a converted amount or a rate snapshot on the order.

#### Scenario: Order total shows a USD conversion row

- GIVEN the active order total is 540 CUP and USD's `exchangeRate` is 540
- WHEN the order screen renders the total
- THEN a conversion row shows 1.00 USD below the primary total
- AND the order record itself stores no USD amount and no rate

#### Scenario: Exchange rate changes after an order is completed

- GIVEN a completed order was created when USD's `exchangeRate` was 540
- WHEN the rate is later changed to 500 and the same completed order is viewed again
- THEN the conversion row recomputes using the current rate (500), producing a different displayed USD amount
- AND the order's stored primary-currency total does not change

### Requirement: Only Active Currencies Are Shown

The system MUST render a conversion row only for currencies marked active.

#### Scenario: Inactive currency produces no row

- GIVEN USD is marked inactive
- WHEN an order total is rendered
- THEN no USD conversion row is shown

## Design-Level / Data-Level Open Questions (for sdd-design)

- The exact default numeric `exchangeRate` seeded for USD is a real-world financial value that changes over time and is NOT fixed by this spec or by the proposal (the proposal explicitly flags it as unresolved). `sdd-design`/implementation MUST treat the seeded rate as an editable configuration value, not a hardcoded constant, and MUST NOT ship a stale rate as if it were a permanent business rule.
