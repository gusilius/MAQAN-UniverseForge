# WorldForge Economy v1

## Canonical ledger

TreasuryService.current() is the pure-Java access point to the single world-owned financial/resource ledger. WorldForgeMod owns its load/save lifecycle but addon simulation and tests do not depend on the Minecraft-bound mod entrypoint. It is persisted at worldforge/wf_treasury.json, loaded before addon state restoration, checkpointed every 6,000 server ticks, and atomically replaced on save. If the existing ledger cannot be read, saving is disabled for that session so the original file is not overwritten.

Silver is the world base money unit; the legacy CityTax UI's gold is a 5:1 display denomination over the same silver account, not a separate currency balance. Each account has a stable ID, owner, currency/resource, cash balance, credit limit, debt and bankruptcy flag. Transfers between accounts are atomic and create matching debit/credit entries with one transaction ID. Every entry carries a world-minute timestamp, category, reference and post-transaction balance.

## Account conventions

| Owner | Account ID | Currency |
|---|---|---|
| Realm treasury | realm:<realm-id> | silver |
| Settlement money | settlement:<dimension>:<settlement-id> | silver |
| Settlement commodity stock | stock:<dimension>:<settlement-id>:<resource-id> | resource ID |
| Army budget | army:<army-id> | silver |

Settlement stock remains a compatibility mirror for existing UI and simulation APIs; account balances in the world ledger are authoritative. Legacy settlement inventories are imported when an account is first opened. Settlement account IDs are scoped by Minecraft dimension so identically named settlements in separate worlds cannot share money or goods. Previous unscoped accounts are migrated into the dimension-scoped account on load. Existing world treasury accounts win over stale stock snapshots on subsequent loads.

## Connected flows

- Building production credits commodity accounts with PRODUCTION entries; recipe inputs, population food, comfort goods and construction materials debit the same resource accounts.
- Settlement tax income, building upkeep, market purchases/sales, raid losses and character consumption are journaled.
- Caravan routes transfer the commodity between settlement accounts and transfer silver between treasury accounts. The price spread is divided between seller proceeds and a recorded market fee. Routes can configure an ad-valorem customs duty (0–50%) paid by the importer to a destination realm treasury.
- CityTax remittance and grants transfer silver atomically between settlement and realm accounts. A configured settlement crown share is remitted as a paired TAX transfer; grants are paired GRANT transfers. The legacy TreasuryApi facade presents realm balances in gold while using the same silver-denominated account as Statecraft.
- Statecraft realm taxes and vassal tribute are booked to realm accounts. Realms with a numeric taxable_base property are assessed automatically on the shared world calendar; government expenditure is constrained by available funds.
- Realm budgets can fund dedicated army accounts; actual troop payroll is now grouped by realm and army/garrison, funded from the realm treasury into the dedicated army account, and debited as a WAGE entry. The daily funding and payroll references are idempotent and recover from a stale troop/Statecraft snapshot. Unit upkeep is authored in gold and converted to the canonical silver base unit at the 5:1 display rate.
- Credit limits, loan origination, interest accrual, repayment and bankruptcy state are available through the same ledger.

## Price formation and logistics

Settlement markets retain their current local base-price and demand-pressure model, with buy/sell margins. Existing settlement maps continue to own warehouse partitions, road connectivity and throughput-limited logistics. These map warehouses are inventory partitions of the settlement's aggregate stock, not independent ledger accounts yet.

## Migration and compatibility

- The ledger is stored separately from addon JSON, so all modules can reference one source of truth.
- Existing settlement stock is lazily migrated to resource accounts on load. Public stock-map writes from older addons are reconciled into the account ledger when read or saved, preserving compatibility while the ledger remains authoritative.
- Existing Statecraft treasury snapshots seed realm accounts only when the canonical account does not already exist.
- Settlement.stock and Statecraft.Treasury.balance are read-compatible mirrors; new money/resource writes must go through the ledger API.

## Stage IX remaining before acceptance

- Automatic realm assessment currently requires a configured taxable_base property. A derived tax base from population, land, trade and economic activity is not yet implemented. Tax and vassal tribute postings are idempotent across interrupted saves; settlement crown remittances reconcile their paid total from the canonical journal.
- Troop roster payroll is connected to realm and dedicated army accounts. Failure to pay now triggers desertion rather than recording a false combat death; a deserter is demobilized into the nearest same-dimension settlement when available, retaining the same Character ID. Individual soldier food/equipment consumption and the OperationalSupply ration store are not yet backed by resource accounts; field supply remains a separate abstract logistics state.
- Warehouse nodes and each logistics leg need independent subaccounts/transfer entries so every intra-settlement warehouse movement is auditable, not only the aggregate settlement stock.
- Configured route customs duties are supported, but border-derived tariff schedules, border controls, travel time, route risk, cargo losses and transport costs still need to affect route settlement.
- Global cross-settlement price discovery and state budget dashboards are not yet unified across every UI workspace.
- Long-running journal retention, archival/compaction and crash-recovery replay tests are required before production-scale world saves.
- CI and Forge runtime tests must pass on the final head; no in-game acceptance is claimed by this contract.
- Latest financial hardening changes add journal-backed idempotency and troop payroll integration. A new CI run for the final PR head has not yet been observed; compilation and tests remain unverified until that run completes.
