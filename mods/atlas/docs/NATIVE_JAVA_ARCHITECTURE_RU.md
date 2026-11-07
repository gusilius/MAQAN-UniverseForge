# MAQAN Galactic Navigator — гибридная архитектура Java + Rust

## Разделение ответственности

### Minecraft / Forge / Cosmic Horizons (Java)
- Forge lifecycle, игровые события, Minecraft Level, entities, chunks и registries.
- Обнаружение Cosmic Horizons datapacks, метаданные небесных тел и связь с измерениями.
- Оригинальные кубические модели и текстуры Cosmic Horizons — сохраняются без замены.
- Активный Minecraft/LWJGL render lifecycle, игровые ресурсы, input/focus и Minecraft Font.
- Захват исследованных чанков, сохранения, совместимость и сетевые сообщения.
- Тонкий host adapter: проверяет пакет Rust и исполняет его через текущий `GuiGraphics`, не создавая отдельного окна или GL-контекста.

### Rust — MAQAN Visual Engine и вычислительное ядро
- Дизайн-токены, геометрия, композиция backdrop/panel/button и построение кадра.
- Анимированный космический фон: звёзды, nebula glow, движущийся световой sweep и декоративные линии.
- Стиль панелей, рамок, кнопок, hover/disabled-состояния и мягкие glow-эффекты.
- Текстовые команды, роли, позиции и содержимое; Minecraft Font пока выполняет glyph rasterization в host adapter.
- Пакетные draw commands, передаваемые одним JNI-вызовом на компонентный batch.
- Пространственные индексы, запросы карты, геометрия, кластеризация, visibility culling и поиск маршрутов по исследованным чанкам.

Rust не получает Minecraft-объекты, не загружает чанки, не раскрывает неисследованные территории, не владеет сохранениями и не продвигает собственные игровые часы.

## Формат кадра и JNI

- `ui::UiFrame` содержит размеры кадра, scale и упорядоченные команды.
- Типы: `Rect`, `Color`, `TextRole`, `ButtonState`.
- Команды: fill, stroke, line, text, clip и radial glow.
- Пакет little-endian: magic `MQUI`, protocol version 1, UTF-8 строки.
- JNI exports ABI 1 уже существуют: handshake, animated chrome, panel, button и explored-chunk route.
- Java decoder проверяет версию, размеры, количество команд, конечность координат и длину строк перед исполнением.
- Для glow Java host rasterizes ограниченное число scanlines через текущий GuiGraphics. Это не отдельный GPU backend и не новый GL context.

**Важное разграничение:** композиция и параметры этих элементов уже формируются Rust, но непосредственная отправка примитивов в текущий Minecraft renderer и glyph rasterization пока выполняются Java host adapter. Полный перенос GPU submission в Rust/OpenGL — отдельный следующий этап после проверки JNI и render-state lifecycle.

## Текущая интеграция экранов

Через общий Rust path уже проходят:
- основной Galactic Navigator: backdrop, верхняя панель, общие панели и кнопки;
- Surface Map и 3D Terrain: backdrop, header panel, общие кнопки и input frames;
- Journey Planner: backdrop, панели и кнопки;
- Orbit settings и Body orbit settings: общие backdrop/panel/button компоненты;
- waypoint manager/editor и player marker screen: backdrop и общие widget skins.

Карточки данных, текст описаний, сложная геометрия карт, орбитальные схемы, WorldForge overlays, 2D/3D terrain, hit testing и игровая логика остаются в соответствующих Java-экранах. Их нельзя удалять или заменять декоративными заглушками: переводить будем по компонентам, сохраняя всю интерактивность.

## Native packaging

- Cargo crate выпускает `rlib + cdylib`, без внешних runtime dependencies.
- CI собирает Windows x86_64, Linux x86_64 и macOS native artifacts.
- Build job скачивает platform artifacts и добавляет их в ресурсы Forge JAR.
- При старте Java выбирает подходящий ресурс, извлекает библиотеку во временный каталог и загружает через `System.load`.
- `-Dmaqan.native.disabled=true` оставляет Java fallback; `-Dmaqan.native.path=...` позволяет явно указать библиотеку.
- Native path нельзя считать проверенным в реальной игре до runtime smoke test на Windows/Forge.

## Данные и время

1. Java наблюдает игрока через Forge и считывает только текущий уже загруженный чанк.
2. Java остаётся владельцем сохранения исследованных территорий.
3. Java передаёт в Rust неизменяемые примитивные snapshots/deltas.
4. Rust возвращает индексы, геометрию, маршрут или пакет UI-команд; игровые объекты остаются в Java.
5. Java проверяет native-результаты и исполняет draw commands в текущем кадре Minecraft.
6. Если библиотека отсутствует, ABI несовместим или пакет повреждён, остаётся Java fallback.

Существующий `NavigatorExplorationStore` остаётся владельцем persistence. `CosmicMotionClock` и `NativeOrbitalSimulation` остаются единым источником орбитальной фазы/позиции для игрового рендеринга и Навигатора. Вторые часы в Rust не создаются.

## Следующие этапы

1. Проверить JNI exports, packet decoder, resource extraction и Forge render lifecycle на Windows.
2. Перевести типографику, input frames, tabs, tooltips, context menus, map labels и остальные общие UI-компоненты на Rust command batches.
3. Ввести экранное scene graph/layout API, чтобы экраны передавали Rust семантические компоненты, а не рисовали каждый элемент напрямую.
4. После профилирования добавить настоящий Rust OpenGL batch renderer, использующий текущий Minecraft context; обязательно сохранять/восстанавливать GL state и не вызывать JNI на каждый примитив.
5. Переводить оставшиеся экраны по одному, сохраняя input/focus/accessibility и все существующие действия.
6. Добавить native/fallback integration tests и реальные игровые smoke tests.

## Сохранённые требования продукта

- Источник небесных тел — Cosmic Horizons и загруженные datapacks; никаких выдуманных планет и демонстрационных каталогов.
- Сохранить оригинальные кубические модели и текстуры Cosmic Horizons.
- Единые часы синхронизируют орбитальное и осевое движение в реальном мире и Навигаторе.
- Иерархия: галактика, сектор, система, планета, спутник.
- Карты планет сохраняют fog-of-war, исследованные чанки, слои глубины, метки игрока, точки маршрута, разные режимы путешествий и 2D/3D рельеф.
- Маршруты не должны использовать неисследованные чанки или загружать их ради прокладки пути.
- WorldForge — необязательный авторитетный источник политических/цивилизационных слоёв; Navigator только отображает эти данные.
- Никакой зависимости от Xaero или JourneyMap.

## Производительность и безопасность

- JNI работает пакетно, а не на каждый пиксель или draw primitive.
- Ограничивать число команд, длину строк и размеры входных массивов на Java boundary.
- Не позволять Rust менять глобальное состояние рендера без симметричного восстановления.
- Не заменять Minecraft/OpenGL renderer на wgpu без отдельного доказанного backend-плана: текущий графический контекст принадлежит Minecraft.
- Сохранять Java fallback до тех пор, пока native path не проверен на реальной игре.
