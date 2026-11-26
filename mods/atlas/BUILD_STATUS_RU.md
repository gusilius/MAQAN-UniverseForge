# MAQAN Galactic Navigator — статус интеграции Cosmic Horizons

## Каноническая структура
- Единственный Gradle-проект расположен в корне репозитория.
- Сборка GitHub Actions запускает `gradle --no-daemon build` из корня.
- Старая вложенная копия `MAQAN-Galactic-Navigator/` подлежит удалению после сверки дубликатов; уникальные корневые исходники сохраняются.
- `NativeOrbitalSimulation.java` из основной ветки сохранён.

## Cosmic Horizons
- Каталог в клиенте строится из списка `CosmosModVariables.WorldVariables.datapack`, который синхронизирует сам Cosmic Horizons.
- Внешние datapack JSON остаются единственным источником данных. Встроенные каталоги удаляются; при отсутствии синхронизации экран показывает пустое состояние.
- Ключ системы строится из `local_id`, а не из `attached_dimention_id`: несколько систем могут использовать одну dimension.
- Данные GUI-категорий, travel_dimension, origin_x/y/z и координаты planet travel_x/y/z сохраняются для навигации.
- Типы GUI-объектов нормализуются отдельно от декоративного поля type (например Rocky/Gas), чтобы фильтры Planet/Moon/Star не ломались.

## Planet preview
- Рендерер использует 6 плоских граней куба и 4×2 cubemap-cross atlas Cosmic Horizons.
- Удалена проекция cube-sphere на UV-сферу.
- Сохранены yaw/pitch/roll и yaw_speed/pitch_speed/roll_speed.
- Текстуры берутся из namespace `cosmos`; PNG и namespaced resource IDs поддерживаются.
- Рендер колец принимает документированное поле scale_radius; старый формат radius + scale_radius также поддерживается.

## Ограничения проверки
- Успешная GitHub Actions сборка подтверждает компиляцию, но не визуальную точность UV в игре.
- Требуется запуск Minecraft Forge 1.20.1 с Cosmic Horizons и несколькими активными cosmic_data JSON, включая два разных local_id с одинаковым attached_dimention_id.
- Серверная телепортация остаётся ограниченной существующими проверками прав, dimension registry, высоты и world border.

## Гибридная архитектура MAQAN
- Добавлен независимый Rust crate native/maqan-core для sparse-индекса посещённых surface tiles.
- CI запускает cargo test --locked.
- На этом этапе Rust ещё не загружается в Minecraft: JNI, нативные бинарники и Java bridge не заявлены как реализованные.
- План карты поверхности и границы ответственности Java/Rust описаны в docs/HYBRID_ARCHITECTURE_RU.md.
