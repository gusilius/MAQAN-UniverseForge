# Статус сборки MAQAN UniverseForge

## Исходный архив

- Использован предоставленный `MAQAN-UniverseForge.zip` (723 записи, ZIP integrity check без ошибок).
- Распакованы Atlas, WorldForge Core, 18 WorldForge addons, Rust crates, ресурсы, тесты и документация.
- Atlas и WorldForge сохранены отдельными проектами и с исходными mod IDs / Forge версиями.

## Выполнено в первом инженерном проходе

- Atlas: общий Java UI theme переведён на минималистичную футуристичную систему — спокойный тёмный фон, плоские поверхности, тонкие разделители, ограниченный cyan accent.
- Atlas Rust UI: согласованы токены цвета, панели, кнопки, вкладки, строки каталога, карточки dossier и context menu; убраны декоративные свечения и анимированные фоновые звёзды/туманности. Сцена планет и орбит не затрагивалась.
- Обновлены Rust unit tests под новую семантику минималистичного chrome.
- WorldForge `ProgressTest`: исправлено устаревшее условие `attrs == 10`; фактический набор содержит 28 атрибутов и должен оставаться расширяемым.
- WorldForge `tools/test-all.sh`: теперь корректно пропускает только тесты, которым нужен отсутствующий datapack fixture, и запускает остальные.
- CI переведён на Windows x64; Atlas native DLL собирается под `x86_64-pc-windows-msvc` и включается в Forge resources.

## Проверки, выполненные локально

- ZIP integrity: PASS.
- WorldForge pure Java addon sources + tests compile (`javac`): PASS (98 addon source files + tests).
- WorldForge logic tests: 24 PASS, 8 SKIP, 0 FAIL.
- Пропуски относятся к map/court/army/war integration tests, которым нужен `testdata/game_of_thrones_datapack.zip`; этого файла в переданном архиве нет.

## Что ещё не подтверждено

- Gradle/Forge build: не запускался — Gradle отсутствует в текущей среде.
- Rust tests/build: не запускались — Cargo/Rust отсутствуют.
- Windows MSVC native DLL и итоговые JAR не созданы в этой среде.
- Forge launch и in-game проверки Cosmic Horizons не выполнялись.
- Rust/Java изменения Atlas пока требуют Windows CI и визуальной проверки в игре.

## Следующий этап

Разделить большой `NavigatorScreen` на переиспользуемые визуальные области без изменения поведения: top navigation, каталог, map canvas, object inspector, route/timeline controls. Затем проверить вкладки Overview / Orbit / Environment / Source / Politics и связанные действия на реальных данных.
