# WorldForge Economy v1

## Canonical ledger

WorldForgeMod.treasury() is the single world-owned financial/resource ledger. It is persisted at worldforge/wf_treasury.json, loaded before addon state restoration, checkpointed every 6,000 server ticks, and atomically replaced on save. If the existing ledger cannot be read, saving is disabled for that session so the original file is not overwritten.

Each account has a stable ID, owner, currency/resource, cash balance, credit limit, debt and bankruptcy flag. Transfers between accounts are atomic and create matching debit/credit entries with one transaction ID. Every entry carries a world-minute timestamp, category, reference and post-transaction balance.

## Account conventions

| Owner | Account ID | Currency |
|---|---|---|
| Realm treasury | realm:<realm-id> | silver |
| Settlement money | settlement:<settlement-id> | silver |
| Settlement commodity stock | stock:<settlement-id>:<resource-id> | resource ID |
| Army budget | army:<army-id> | silver |

Settlement stock remains a compatibility mirror for existing UI and simulation APIs; account balances in the world ledger are authoritative. Legacy settlement inventories are imported when an account is first opened. Existing world treasury accounts win over stale stock snapshots on subsequent loads.

## Connected flows

- Building production credits commodity accounts with PRODUCTION entries; recipe inputs, population food, comfort goods and construction materials debit the same resource accounts.
- Settlement tax income, building upkeep, market purchases/sales, raid losses and character consumption are journaled.
- Caravan routes transfer the commodity between settlement accounts and transfer silver between treasury accounts. The price spread is divided between seller proceeds and a recorded market fee.
- A configured settlement crown share is remitted from its settlement account to realm:<id> as a paired TAX transfer. Unfunded remittances remain pending.
- Statecraft realm taxes and vassal tribute are booked to realm accounts. Government expenditure is constrained by available funds.
- Realm budgets can fund dedicated army accounts; army upkeep can be charged from those accounts.
- Credit limits, loan origination, interest accrual, repayment and bankruptcy state are available through the same ledger.

## Price formation and logistics

Settlement markets retain their current local base-price and demand-pressure model, with buy/sell margins. Existing settlement maps continue to own warehouse partitions, road connectivity and throughput-limited logistics. These map warehouses are inventory partitions of the settlement's aggregate stock, not independent ledger accounts yet.

## Migration and compatibility

- The ledger is stored separately from addon JSON, so all modules can reference one source of truth.
- Existing settlement stock is lazily migrated to resource accounts on load.
- Existing Statecraft treasury snapshots seed realm accounts only when the canonical account does not already exist.
- Settlement.stock and Statecraft.Treasury.balance are read-compatible mirrors; new money/resource writes must go through the ledger API.

## Stage IX remaining before acceptance

- The collectTaxes API is implemented and journaled, but the world calendar does not yet run a complete tax assessment cycle from population, land, trade and vassal taxable bases.
- Army funding/upkeep APIs exist, but the actual troop roster and soldier payroll/food/equipment upkeep adapter is not yet connected.
- Warehouse nodes and each logistics leg need independent subaccounts/transfer entries so every intra-settlement warehouse movement is auditable, not only the aggregate settlement stock.
- Caravan travel time, route risk, losses, tariffs/customs, border controls and transport costs still need to affect the route settlement.
- Global cross-settlement price discovery and state budget dashboards are not yet unified across every UI workspace.
- Long-running journal retention, archival/compaction and crash-recovery replay tests are required before production-scale world saves.
- CI and Forge runtime tests must pass on the final head; no in-game acceptance is claimed by this contract.
