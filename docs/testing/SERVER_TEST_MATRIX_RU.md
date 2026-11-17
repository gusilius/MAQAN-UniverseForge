# Серверная матрица MAQAN / WorldForge

## Принцип

Стенд сокращает время старта и не относящуюся к проверке работу, но не уменьшает население, количество государств, глубину симуляции или число активных доменных систем в полном интеграционном прогоне. PASS одного уровня не подменяет PASS другого.

## Уровни проверки

| Уровень | Среда | Что доказывает |
|---|---|---|
| Logic | \`tools/test-all.sh\` | Доменные алгоритмы, транзакции, идентичность, сериализация и регрессии |
| Native | Rust tests на Windows/Linux | JNI/native compute ABI, алгоритмы и платформенные сборки |
| Addon isolation | \`tools/addon-isolation-server.sh\` | Core-only плюс отдельный Forge boot каждого addon с обязательным runtime dependency closure |
| Combined startup | \`tools/smoke-server.sh\` | Все addons одновременно, Forge load order, команды и совместимость набора |
| Full campaign | \`tools/autonomous-campaign.sh\` | Четырёхпоколенная кампания на полной Planetos geography |
| Domain integration | отдельные сценарии | Сквозные последствия, persistence и физический Minecraft runtime |

## Почему изолированные серверы уменьшены

Изолированные профили используют плоский временный мир, малые view/simulation distance и только обязательную цепочку зависимостей. Это сокращает startup cost и проверяет отсутствие необязательных providers. Они не измеряют производительность и не заменяют кампанию.

Полная Planetos campaign использует все addons и исходный geography package. Население, число государств, поколения, settlements и исторические последствия не урезаются.

## Профили

Граф строится из runtime \`mods.toml\`, а не из compileOnly или порядка сборки. Для каждого addon запускается его обязательное dependency closure; optional providers отсутствуют. Отдельный combined boot проверяет полную совместную установку.

## Сквозные acceptance gates

- NPC/Origins: canonical Character → family/house/traits → save → restart → same UUID, parentage and Origin.
- Politics: canonical ruler → dynasty → succession → title/realm ownership → save/reload.
- Settlements: resident → work/task graph → production → storage/treasury → construction → save/reload.
- RTS/military: recruitment → Character → roster → TroopEntity → formation/command → supply → combat → casualty/injury/estate → save/reload.
- Action execution: typed command → preconditions/revisions → idempotency receipt → side effects → restart recovery.
- Cross-domain: fault injection after each apply stage; restart; no lost/duplicated Character/Origin/Settlement/EventLog/ActionExecution facts.
- Atlas: exact Cosmic Horizons build/datapacks, coordinate units, orbital parity, route transitions, explored-surface persistence and catalog profiling.
- Platform: workspace discovery, provider registration/unload, search/index, inspector, map layers and typed command results.

## Диагностика кампании

Полный server.log, crash reports, GC logs и JVM thread dumps каждые 90 секунд на протяжении всего прогона. Лимита на число дампов нет. Watchdog закрывается только после проверки server-thread stack, повторяющихся ошибок, canonical ledger sizes и GC timeline. Повышение watchdog timeout, уменьшение населения или отключение модулей не являются исправлением.
