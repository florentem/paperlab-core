# PaperLab — ядро

Форк [Paper](https://github.com/PaperMC/Paper) 26.2 для исследования ферм Minecraft.
Даёт инструменты в духе Carpet, адаптированные под особенности Paper — в первую очередь
под **локальные мобкапы**, которых в ваниле и Fabric-модах нет.

Вторая половина проекта — плагин **PaperLab**, он лежит отдельно. Здесь только то, чего
плагином сделать нельзя.

---

## Что добавляет форк

Ветка `lab` добавляет четыре минимальных feature-патча:

1. **`0035-Paper-Lab-hooks.patch`** — базовые точки перехвата:
   * `ChunkMap` (4 точки): наблюдатель вне переписи мобкапа, вне backoff, не расширяет область спавна, не грузит чанки;
   * `RegionizedPlayerChunkLoader` (Moonrise): наблюдатель не делает ticking ни одного чанка;
   * `ActivationRange`: наблюдатель не будит мобов (EAR);
   * `LivingEntity.canBeSeenByAnyone`: мобы не выбирают наблюдателя целью;
   * `NaturalSpawner`: наблюдатель не режет бюджет чанка + 4 метки трассы спавна;
   * `PlayerList.remove`: снять режим наблюдателя при выходе;
   * `MinecraftServer.tickChildren`: `doTick()` бота в фазе соединений;
   * `Commands`: регистрация `/player` и узлов `toggle`/`warp` в ванильный `/tick`;
   * `FillCommand`, `SetBlockCommand`, `CloneCommands`: правило `fillUpdates`;
   * `ServerDebugSubscribers`: отладочные подписки по праву, а не только оператору.

2. **`0036-Capture-and-Playback-hooks.patch`** — интеграция с модом [Capture & Playback](https://modrinth.com/mod/capture-playback) (автор [G4me4u](https://github.com/G4me4u)):
   * `ServerLevel`: вызовы в начале/конце `tick()` и внутри цикла `runBlockEvents()` для синхронизации микротиков и сброса стримов;
   * `SignalGetter`: инъекция редстоун-сигнала 15 в `getSignal` и `getControlInputSignal` при активном воспроизведении дорожки.

3. **`0037-Paper-Lab-item-movement-and-microtiming-hooks.patch`** — хуки логгеров Carpet-TIS-Addition:
   * `ItemEntity`: трекинг создания, деспавна (5 минут) и гибели от урона (`LabItemTracker`);
   * `Entity`: пошаговая раскладка этапов расчета перемещения в `move()` — поршни, сник, коллизии (`LabMovementTracker`);
   * `ServerLevel` и `Level`: перехват событий блоков `doBlockEvent` и смены состояний `setBlock` (`LabMicroTiming`).

4. **`0038-Paper-Lab-per-world-tick-hooks.patch`** — независимый тикрейт и заморозка по мирам (Per-World Tick):
   * `ServerLevelTickRateManager`: персональный менеджер тикрейта для каждого измерения со своей очередью шагов (`step`), спринтов (`sprint`) и заморозки (`freeze`);
   * `MinecraftServer`: планировщик тиков ориентируется на минимальный интервал среди активных миров, тикает только незамороженные миры и их часы;
   * `TickCommand`: перенаправление `/tick` на менеджер мира из контекста отправителя (`/execute in <мир> run tick ...`);
   * `PlayerList`: отправка сетевых пакетов тикрейта (`ClientboundTickingStatePacket`, `ClientboundTickingStepPacket`) игрокам конкретного мира;
   * `org.bukkit.World`: метод `world.getTickManager()` в Paper API.

Основной код живёт обычными исходниками в `paper-server/src/main/java/io/papermc/paper/lab/`
и в патч-систему не входит: `ghost/`, `spawn/`, `bot/`, `rules/`, `command/`, `cplay/`,
`item/`, `movement/`, `microtiming/`, `tick/`.

---

## Ключевые возможности форка

**Режим наблюдателя.** Игрок перестаёт влиять на симуляцию, но продолжает
взаимодействовать с миром: блоки ставятся и ломаются, контейнеры открываются. Не грузит
чанки, не занимает мобкап, не будит мобов, не замечается ими.

Измерено: игрок с `simulation-distance=5` держит 121 тикающий чанк (11×11), наблюдатель —
**ноль**. Одной персональной дистанции симуляции для этого мало: `tickMap` в Moonrise
с радиусом 0 всё равно покрывает чанк под самим игроком, и энтити в нём оживают, стоит
туда войти. Поэтому гасится очередь `tickingQueue` — единственная точка, где чанк
переходит в стадию TICK.

**Трасса спавна.** Движок не сообщает, на каком шаге остановилась попытка появления моба,
а причин несколько и они разного смысла:

```
spawn monster  cap 113307 · passes 4 · position 0 · plugin 0 · spawned 1
spawn ambient  cap 0 · passes 113311 · position 479805 · plugin 0 · spawned 0
```

У монстров всё упирается в кап, у ambient кап свободен, но не проходит позиция.
Столбец `plugin` — единственная причина, которой в чистом Paper быть не должно.

Единицы разные, складывать столбцы нельзя: `cap` и `passes` считаются на проход
«чанк × категория», остальные — на каждую пробуемую позицию.

**Боты.** Настоящие `ServerPlayer` без клиента. `doTick()` вызывается в фазе соединений —
ровно там и в том же порядке относительно `tick()`, что у живого игрока. Плагином это
недостижимо: его планировщик работает в начале `tickChildren`, до фазы уровней.

**Независимый тикрейт по мирам (Per-World Tick).** В ванили `/tick` глобален для всего
сервера. Форк внедряет независимые `ServerLevelTickRateManager` по мирам при включении
правила `perWorldTick`. Это позволяет заморозить оверворлд и прогонять замеры в Энде на
полной скорости или пошагово (`step`), изолировать команды `/tick rate` через `/execute in`,
а плагинам даёт API `world.getTickManager()`. При отключении правила все миры плавно
синхронизируют тикрейт и заморозку с сервером.

**Глубокая диагностика ферм (Carpet-TIS-Addition).** Внутренние хуки в `Entity.move()`,
`ItemEntity` и событиях блоков дают логгерам `/log item`, `/log movement` и `/log microtiming`
полную точность измерений без оверхеда в отключённом состоянии.

---

## Сборка

Нужен **JDK 25**.

```bash
./gradlew applyPatches         # развернуть src/minecraft из патчей
./gradlew createPaperclipJar   # собрать запускаемый jar
```

Готовый jar: `paper-server/build/libs/paper-paperclip-26.2.local-SNAPSHOT.jar`.

### Правило, которое стоило сломанного дерева

`rebuildFeaturePatches` **нельзя** запускать в одном вызове Gradle со сборкой: он сбрасывает
внутренний репозиторий `paper-server/src/minecraft/java` и заново проигрывает серию, а
компиляция в это время читает исходники. Получаются ошибки в файлах, которых вы не трогали.

```bash
./gradlew rebuildFeaturePatches            # правка из внутреннего коммита -> в патч
git status --short paper-server/patches    # должен измениться ТОЛЬКО 00NN-*.patch
./gradlew applyPatches                     # восстановить дерево
./gradlew createPaperclipJar               # и только теперь собирать
```

Остальные грабли, пойманные по ходу, — в `outputs/fork-build-notes-ru.md` основного
репозитория проекта.

---

## Лицензия и сторонние проекты

Наследуется от Paper: **GPL-3.0** для серверной части, **MIT** для API. Подробности —
[LICENSE.md](LICENSE.md), исходный README апстрима — [README-Paper.md](README-Paper.md).

Всё, что добавлено этим форком, распространяется на тех же условиях (GPL-3.0).
Хуки ядра используют концепции и обеспечивают совместимость со следующими проектами:
* **[PaperMC](https://github.com/PaperMC/Paper)** — лицензия GPL-3.0 (сервер) / MIT (API).
* **[Fabric Carpet](https://github.com/gnembon/fabric-carpet)** (автор [gnembon](https://github.com/gnembon)) — лицензия MIT. Архитектура ботов `/player` и команды `/tick`.
* **[Carpet-TIS-Addition](https://github.com/TISUnion/Carpet-TIS-Addition)** (команда [TIS-Union](https://github.com/TISUnion)) — лицензия LGPL-3.0. Точки перехвата в `Entity.move()`, `ItemEntity` и блочных событиях для логгеров `item`, `movement` и `microtiming`.
* **[Capture & Playback](https://modrinth.com/mod/capture-playback)** (автор [G4me4u](https://github.com/G4me4u)) — сетевой протокол G4mespeed; хуки ядра в `ServerLevel` и инъекция редстоун-сигналов в `SignalGetter` написаны заново под GPL-3.0.

