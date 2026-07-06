# Proposal: android-pos-mvp (Slice A — offline single-device POS core)

## Intent

Ship the minimum end-to-end, on-device selling path for a greenfield offline Android POS: scan/lookup a product, sell it, decrement stock atomically, reconcile a shift's cash. This proves the ported domain (from the `idos-pos` backend, reference-only) works on one device with no server, no multi-tenancy, no concurrency arbitration — the class of race conditions the backend solved does not exist here (single writer). It is the foundation every later slice builds on.

## Scope

### In Scope (Slice A)
- **Product catalog**: CRUD + unique code/barcode + category + unit of measure; barcode lookup via camera scan.
- **Inventory as movement ledger**: IN/OUT/ADJUST, minimum-stock field; single-owner mutation primitive (no double-count).
- **Sales order (SALE only)**: atomic order + lines + stock-decrement (OUT) via Room `@Transaction`; per-day sequential order number (no store scoping); payment-method resolution defaulting to CASH.
- **Cash session (shift)**: single open session per device; open/close; `expectedBalance = opening + Σdeposits − Σwithdrawals + Σcash sales`; manual cash movements.
- **Alt-currency display rows**: display-only conversion below total, `total / exchangeRate`.
- **Seeded catalogs**: payment methods CASH (`affectsCashBalance=true`) + TRANSFER (`false`); currency — see Decisions.
- **Minimal permission gate**: local manager-PIN guarding price edits + ADJUST movements (see Decisions).

### Out of Scope (deferred to named changes)
- **License enforcement → `android-pos-licensing`** (fast-follow; see Decisions).
- Void-with-reason, SAF backup/export → `android-pos-hardening` (Slice B).
- Returns flow, reports, minimum-stock alerts → `android-pos-reporting` (Slice C).
- Multi-store, JWT auth, cash-register catalog, per-store price override → **permanently out** (single-install product).
- Receipt printing → out of v1 entirely.

## Capabilities

### New Capabilities
- `product-catalog`: product CRUD, code/barcode uniqueness, barcode lookup.
- `inventory-ledger`: IN/OUT/ADJUST movements, single-owner mutation, minimum-stock tracking.
- `sales-order`: SALE-only atomic order create + stock decrement + daily sequence.
- `cash-session`: single-shift open/close, expectedBalance, cash movements.
- `payment-method-catalog`: seeded CASH/TRANSFER with `affectsCashBalance`.
- `currency-display`: display-only alt-currency conversion rows.
- `permission-gate`: local manager-PIN guard for sensitive actions.

### Modified Capabilities
None (greenfield — no existing specs).

## Decisions (firm — sdd-design details HOW, not WHICH)

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| Persistence | Room (SQLite) | **Confirm** | Domain is relational (order↔lines↔product, session aggregates); Jetpack-official; `@Transaction` gives the one real requirement (atomicity), volume trivial for one shop. |
| UI toolkit | Compose | **Confirm** | Greenfield removes switching-cost argument; live totals/conversions/stock are Compose's textbook reactive case. |
| Barcode | CameraX + MLKit | **Confirm** | Both Google-maintained (ZXing is maintenance-only); custom scan UI + clean Compose `PreviewView` interop; moderate cost. |
| DI | **Manual constructor injection** | Override | ~15–25 class MVP does not justify Hilt ceremony or Koin's runtime-error risk; zero-dependency wiring is proportionate. Revisit at Slice B if scope grows. |
| Module structure | **Single Gradle app module** | — | One APK, no independent reusability; organize by feature package (`catalog`, `inventory`, `sales`, `cashsession`, `currency`, `permission`). Do NOT import the backend's multi-module reactor. |
| Permission gate ownership | **This change (Slice A)** | — | Product price edit and ADJUST movements exist in the MVP and are stock/price-integrity sensitive on a cashier-controlled device. A single reusable PIN-check primitive is born here; full reports gating stays in later slices. NOT a role/user/account system. |
| Seed currency | Primary **CUP**, alt **USD** | — | Mirrors reference deployment; `exchangeRate` = primary units per 1 alt unit. Exact default rate is a **spec-level detail** — sdd-spec to confirm. |

### License enforcement: deferred to a fast-following change, distribution-gated

**Decision: license enforcement is OUT of `android-pos-mvp` and OWNED by a fast-following change `android-pos-licensing`** — with a **binding non-goal: no MVP build may be distributed to any paying customer until `android-pos-licensing` ships.**

Justification: the license subsystem (on-device JWS verify, activation UI, installation-ID binding, anti-tamper heartbeat, status/enforcement gate) is architecturally independent of the POS domain — it touches app startup and a status/activation screen, not the shape of Product/Inventory/Order/CashSession. Deferring it therefore costs ~zero rework in Slice A code. The real business risk (a commercial product copyable from day one) only materializes **at commercial distribution**, so it is fully contained by gating distribution rather than bloating the first slice (+1.5–3 wk of an isolated subsystem). This keeps MVP discipline AND neutralizes the risk. The licensing change adopts installation-ID (locally-persisted UUID) binding + single-activation issuance, and reuses the offline JWS approach; activation mechanism (QR-scan vs file-import) is decided there.

### Vendor-side tooling boundary (explicit)

Extending `tools/license-cli/` (which lives in the **reference repo** `D:\Proyectos\idos-pos`, a separate codebase) to issue Android licenses is **OUT of this change entirely** and is a concern of `android-pos-licensing`, not silently bundled anywhere. This Android project produces no vendor tooling.

## Effort framing

Revised total ≈ **8.5–10 calendar weeks** for a shippable licensed product (≈7 wk POS core across Slices A–C + 1.5–3 wk licensing), with active AI-assisted development and daily on-device human review. Slice A alone is the smaller front portion of the ~7 wk.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/` (new Gradle module) | New | Single-module Kotlin/Compose app; feature packages listed above |
| Room schema (v1) | New | product, category, unit_measure, inventory, inventory_movement, `orders`, order_line, cash_session, cash_movement, payment_method, currency |
| Seed data | New | CASH/TRANSFER payment methods; CUP primary + USD alt currency |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| MVP build leaks to a customer before licensing lands | Med | Binding distribution non-goal above; treat licensing as the immediate next change |
| Installation-ID binding weaker than desktop fingerprint | Med | Documented in `android-pos-licensing`; mitigate via single-activation vendor issuance |
| Team Android/Compose experience unknown | Med | Manual DI + Compose keep the stack minimal; re-estimate if Views/Hilt fluency exists |
| Seed currency rate wrong at spec time | Low | Flagged as spec-level detail for sdd-spec confirmation |

## Rollback Plan

Greenfield, no production data, no existing specs. Rollback = discard the change folder and unmerged Slice A commits; nothing downstream depends on it yet. Room schema is v1 (no migration to reverse).

## Dependencies

- Android SDK / Gradle project skeleton (established in first sdd-apply; re-detect testing per config.yaml).
- Domain knowledge from `idos-pos` reference (consulted, not imported).

## Success Criteria

- [ ] Operator can create/edit products and look one up by camera barcode scan.
- [ ] Stock changes only via ledger movements; a sale writes an atomic OUT + order in one transaction.
- [ ] A shift opens, records sales/cash movements, and closes with a correct `expectedBalance`.
- [ ] Order total shows an alt-currency (USD) conversion row.
- [ ] Price edits and ADJUST movements are blocked without the manager PIN.
- [ ] License enforcement is explicitly absent and the distribution non-goal is recorded for the next change.
