# Чужая работа в PaperLab Core

Этот репозиторий — форк [Paper](https://github.com/PaperMC/Paper) и наследует его
лицензирование: **GPL-3.0** для сервера, MIT для API. См. [LICENSE.md](LICENSE.md).

Ниже — то, что взято не у Paper. Список полный.

## Carpet Mod — MIT

Автор: **gnembon**, <https://github.com/gnembon/fabric-carpet>

Взята **грамматика команд**: имена и порядок аргументов `/player`
(`spawn`, `attack`, `use`, `jump`, `drop`, `dropStack`, `swapHands`, `sneak`,
`unsneak`, `sprint`, `unsprint`, `mount`, `dismount`, `respawn`, `continuous`,
`interval`, `once`) и наши узлы `toggle` / `warp` в ванильном `/tick`. Реализация
своя — она обязана быть своей, у Paper другое устройство сервера, — но имена
намеренно совпадают: команда, набранная по памяти с Carpet, должна работать.

MIT требует сохранять уведомление об авторстве:

```
MIT License

Copyright (c) 2020 gnembon

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR OTHER DEALINGS IN THE SOFTWARE.
```

MIT совместима с GPL-3.0.

## Идеи без кода

| Проект | Лицензия | Что переняли |
|---|---|---|
| [Carpet TIS Addition](https://github.com/TISUnion/Carpet-TIS-Addition) | LGPL-3.0 | постановка задачи для хуков логгеров `item`, `movement`, `microtiming` |
| [Capture & Playback / G4mespeed](https://github.com/G4me4u/g4mespeed) | GPL-2.0 | что именно серверу нужно перехватывать, чтобы воспроизводить сигналы |

Ни строки чужого кода в обоих случаях. Для G4mespeed это принципиально: GPL-2.0
без оговорки «or later» несовместима с GPL-3.0, смешивать нельзя. Разбор самого
протокола живёт в репозитории плагина, там же подробности — см. его `THIRD-PARTY.md`.
