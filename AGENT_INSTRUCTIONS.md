# TempoScore — подробная инструкция для coding agent

## 0. Сначала прочитай

Перед изменениями обязательно прочитай полностью:

1. `SPEC.md`
2. `README.md`
3. `docs/architecture.md`
4. `docs/realtime-rules.md`

Если код расходится со спецификацией, сначала выясни, является ли код временным scaffold. Для `BeatTracker` и `ScoreStaffView` это прямо так: они намеренно упрощены.

## 1. Цель проекта одной фразой

Android 10 приложение загружает MIDI, показывает текущую/следующую музыкальную информацию и через микрофон следует за **темпом и beat phase живого ансамбля**, не распознавая ноты.

## 2. Неподлежащие переинтерпретации требования

Это важно. Не «улучшай» продукт в другую сторону.

- Live input = встроенный/выбранный Android microphone.
- **Не подключать MIDI-клавиатуру.**
- Не добавлять Android MIDI device discovery/input.
- Не делать pitch detection.
- Не делать audio-to-MIDI.
- Не делать chord recognition.
- Ноты берутся из MIDI-файла.
- Из аудио извлекается ритм: onsets, beat period, phase, BPM, confidence.
- Базовый BPM/prior идёт из MIDI.
- `targetSdk` должен оставаться **29**.
- `compileSdk = 34` допустим и выбран намеренно.
- C++/NDK/CMake/Oboe являются частью архитектуры, а не временной зависимостью.
- Основной audio/DSP path должен быть native.

## 3. Исходный build/tooling baseline

Проект сознательно повторяет проверенный стиль `einhander/piano`:

```text
AGP        8.2.0
Kotlin     1.9.20
Gradle     8.5
JDK        17
compileSdk 34
targetSdk  29
minSdk     26
NDK        26.1.10909125
CMake      3.22.1
C++        17
ABI        arm64-v8a, armeabi-v7a
```

Oboe подключён через Prefab:

```kotlin
buildFeatures {
    prefab = true
}

dependencies {
    implementation("com.google.oboe:oboe:1.10.0")
}
```

CMake:

```cmake
find_package(oboe REQUIRED CONFIG)
target_link_libraries(temposcore_native PRIVATE oboe::oboe)
```

Не заменяй Prefab на случайный vendored fork без причины.

## 4. Проверка baseline перед работой

На машине с Android SDK:

```bash
cp local.properties.template local.properties
# исправить sdk.dir

./build.sh debug
./build.sh test
```

Если bootstrap wrapper был заменён официальным Gradle wrapper — это нормально.

Не начинай архитектурный рефакторинг, пока baseline не собирается либо пока причина build failure не локализована.

## 5. Текущая структура

```text
app/src/main/java/com/einhander/temposcore/
  MainActivity.kt
  NativeAudioBridge.kt
  midi/
    MidiModels.kt
    MidiFileParser.kt
  score/
    ScoreNavigator.kt
  transport/
    TransportSnapshot.kt
  ui/
    ScoreStaffView.kt

app/src/main/cpp/
  native_audio_jni.cpp
  audio/
    OboeInputEngine.h/.cpp
  beat/
    BeatTracker.h/.cpp
  transport/
    LiveTransport.h/.cpp
```

Сохраняй границы ответственности.

## 6. Kotlin responsibilities

Kotlin отвечает за:

- Android lifecycle;
- runtime microphone permission;
- Storage Access Framework;
- чтение MIDI bytes;
- MIDI model;
- отображение;
- выбор стартовой позиции;
- polling опубликованного native state;
- UI diagnostics.

Kotlin **не должен** становиться местом production audio DSP.

## 7. C++ responsibilities

C++ отвечает за:

- Oboe stream;
- native PCM handling;
- ring buffer для analyzer;
- STFT/onset DSP;
- tempo/phase tracking;
- confidence;
- live musical transport;
- lock-free/atomic публикацию state в JNI.

## 8. JNI philosophy

JNI должен быть узким.

Хороший контракт:

```text
initialize(expectedBpm, startBeat)
start()
stop()
setExpectedBpm(...)
resetPosition(...)
getState()
```

Не передавай каждый audio block в Kotlin.
Не вызывай JNI из Oboe callback.
Не создавай сотни JNI вызовов для отдельных нот.

## 9. Самое важное правило real-time

`onAudioReady()` — hard RT.

В нём нельзя:

- `new/delete`;
- `malloc/free`;
- mutex;
- condition variable;
- blocking queue;
- logging;
- JNI;
- Kotlin;
- file I/O;
- network;
- thread creation;
- STL resize/reallocation.

До production tracker текущий lightweight energy detector допустим как scaffold. Когда появится STFT, перенеси DSP на analyzer thread.

## 10. Как должен выглядеть production audio pipeline

Реализуй поэтапно:

```text
Oboe callback
  |
  +-- copy mono PCM into PREALLOCATED SPSC ring
                         |
                         v
                 Analyzer thread
                 - windowing
                 - FFT
                 - spectral flux
                 - onset envelope
                 - tempo estimator
                 - beat PLL
                         |
                         v
                 latest BeatObservation
                    atomic/seqlock
                         |
                         v
                 audio callback
                 LiveTransport advance
                         |
                         v
                 atomic TransportState
                         |
                         v
                       JNI/UI
```

### Почему transport остаётся привязан к callback

Он должен двигаться по реальному числу audio frames, а не по scheduler jitter analyzer thread или Android UI.

## 11. Ring buffer

Нужен SPSC: producer = Oboe callback, consumer = analyzer thread.

Требования:

- память выделяется до `requestStart()`;
- power-of-two capacity удобна, но не обязательна;
- producer никогда не блокируется;
- при overflow увеличивать dropped counter и сбрасывать/пропускать данные по явной политике;
- никаких mutex;
- analyzer может ждать через неблокирующий polling + condition вне RT, либо короткий sleep на своём потоке; callback не ждёт никогда.

Сначала добавь unit-test ring buffer на host, затем используй в Android build.

## 12. Production onset detector — рекомендуемая первая реализация

Не начинай с neural network.

### 12.1 Preprocessing

- actual Oboe sample rate;
- mono;
- remove DC / very slow drift;
- optional gentle normalization, но никакого AGC, который меняет signal непредсказуемо внутри собственного DSP.

### 12.2 STFT

Стартовая сетка:

```text
FFT 1024 or 2048
hop 256 or 512
Hann
```

Не объявляй один набор параметров правильным без A/B тестов.

### 12.3 Spectral flux

Для каждого bin:

```text
flux_k = max(0, magnitude_k[t] - magnitude_k[t-1])
```

Суммировать отдельно по band'ам:

```text
low  ~40..220 Hz
mid  ~220..2000 Hz
high ~2000..10000 Hz
```

Затем привести band envelopes к сопоставимым масштабам и смешать.

Почему multiband: drums+bass+guitar могут давать beat evidence в разных частях спектра.

### 12.4 Peak/onset picking

Используй adaptive local threshold, например EMA/median + margin.

Нужны:

- refractory interval;
- peak prominence;
- защита от chatter;
- timestamp onset в audio-frame coordinates.

Не привязывай onset timestamp к `System.nanoTime()`.

## 13. Tempo estimator

### Inputs

- onset envelope / onset timestamps;
- MIDI expected BPM;
- previous transport BPM;
- previous confidence.

### Required properties

- prior around MIDI BPM;
- continuity;
- half/double tempo resolution;
- bounded acceleration;
- graceful decay when evidence disappears.

Начальный допустимый диапазон:

```text
0.55 * midiExpected <= live <= 1.80 * midiExpected
```

Для user setting later можно сделать ±10/20/30/50%.

### Не делать

```text
BPM = independentTempoEstimate(last2Seconds)
```

каждый раз с полной заменой state.

Это приведёт к 60/120/240 jumps.

## 14. Beat PLL / phase

State минимум:

```text
periodFrames
phaseFrame
frequency/tempo drift
confidence
```

Когда onset близок к predicted beat:

```text
phaseError = observedFrame - predictedBeatFrame
phase += Kp * phaseError
tempo += Ki * phaseError
```

`Kp/Ki` должны быть ограничены. Phase correction визуально должна быть мягкой.

Onset далеко от predicted beat не обязан быть ошибкой: это может быть восьмая/синкопа. Не притягивай к сетке каждый transient.

## 15. MIDI prior при tempo changes

Не держи `expectedBpm` навечно равным первому tempo event.

Добавь в Kotlin/model функцию:

```text
tempoAtQuarterBeat(position)
```

Когда transport переходит tempo event, обновляй native prior.

Лучше хранить `liveTempoScale` относительно MIDI tempo map, чтобы MIDI rit./accel. сохранялись.

Пример:

```text
midi 120, live 114 => scale .95
next midi tempo 140 => expected live ~133
```

## 16. Позиция и reset

Transport position — quarter-note beats.

MVP:

```text
resetPosition(0.0)
```

Следующий UI milestone:

```text
Start from bar N
```

Нужно реализовать bar->quarterBeat преобразование с учётом time-signature map.

Не пытайся «угадать номер такта» только по BPM.

## 17. Что делать при tracking confidence loss

Не сбрасывай транспорт к MIDI BPM мгновенно.

Политика:

- коротко нет evidence: hold last stable transport BPM;
- confidence decay;
- UI -> WEAK;
- длительно нет evidence -> LOST;
- продолжать prediction ограниченное время;
- автоматический freeze сделать отдельной настройкой, потому что музыкальная пауза не равна остановке исполнения.

## 18. MIDI parser

Текущий dependency-free parser намеренно небольшой.

Перед расширением добавляй regression tests.

Обязательно сохранять:

- running status;
- format 0/1;
- tempo events;
- time signatures;
- note pairing по track/channel/pitch;
- защита от malformed lengths.

SMPTE пока должен завершаться понятной ошибкой, а не неверно интерпретироваться как PPQ.

## 19. Нотное отображение

Не трать первые итерации на идеальную гравировку.

Порядок:

1. устойчивый transport;
2. piano-roll/timing view;
3. Now/Next;
4. scroll/fixed playhead UX;
5. затем proper notation.

Текущий `ScoreStaffView` — визуальная заглушка.

Если реализуешь настоящий staff renderer, раздели:

```text
MidiScore
 -> QuantizedNotationModel
 -> Layout/Engraving
 -> Android View rendering
```

Не смешивай MIDI parser и Canvas layout.

## 20. Debug/diagnostics, которые агент должен добавить рано

Сделай developer panel с:

- Oboe actual API (AAudio/OpenSL ES);
- sample rate;
- channel count;
- frames per callback/burst;
- RMS;
- onset envelope;
- detected BPM;
- transport BPM;
- phase error;
- confidence;
- ring overflow count;
- stream disconnect/error count.

Это важнее красивого UI на этапе DSP.

## 21. Offline DSP harness

До долгой отладки на телефоне сделай возможность прогонять tracker на заранее подготовленном PCM/WAV вне Android UI.

Желательно вынести pure C++ DSP так, чтобы он собирался host compiler'ом без Oboe/JNI.

Минимальные тестовые сигналы:

- click 120 BPM;
- click 90 BPM при MIDI prior 120;
- постепенный ramp 100 -> 130 BPM;
- восьмые при prior 120, чтобы не получить 240;
- kick+snare synthetic pattern;
- silence gap;
- off-beat syncopation.

Метрики:

- median BPM error;
- 95 percentile BPM error;
- phase error in ms/beats;
- acquisition time;
- number of octave errors (x0.5/x2).

## 22. Test strategy

### JVM

```bash
./gradlew :app:testDebugUnitTest
```

Тестировать:

- MIDI parser;
- bar/beat conversion;
- score navigator;
- tempo-map math.

### Host C++

Отдельные tests для:

- ring buffer;
- onset envelope;
- tempo estimator;
- PLL;
- transport math.

### Android device

Проверять минимум на Android 10:

1. permission flow;
2. Oboe open/start/stop 20 раз;
3. screen rotation/lifecycle если orientation policy изменится;
4. microphone unplug/reroute where applicable;
5. 10+ minute continuous run;
6. drums;
7. guitar+bass;
8. full mix;
9. quiet passage.

## 23. Performance profiling

Не оптимизируй вслепую.

Замерь:

- callback duration vs callback period;
- analyzer thread CPU;
- ring occupancy;
- dropped blocks;
- UI frame time;
- JNI polling overhead.

FFT не должен работать на main thread.

## 24. Error handling

### Oboe open failure

Возвращать ошибку в Kotlin/UI, не crash.

### Stream disconnect

Error callback только фиксирует состояние/сигнализирует management layer. Переоткрытие stream делать вне RT callback.

### Invalid MIDI

Показывать понятную ошибку и оставлять предыдущий score нетронутым либо явно выгружать его — выбрать одну стабильную политику и покрыть тестом.

## 25. Lifecycle

MVP работает только пока Activity активна. Не добавляй foreground service, пока нет требования продолжать listening при выключенном экране/в фоне.

Если foreground service появится позднее, native engine должен принадлежать process/service layer, а не случайно пересоздаваться на каждый Activity instance.

## 26. Dependency policy

Добавляй dependency только если она решает конкретную проблему.

Предпочтения:

- Oboe — уже выбран;
- FFT: небольшая C/C++ library с понятной лицензией или собственный хорошо протестированный wrapper;
- не добавлять большой audio framework ради одного FFT;
- не добавлять ML runtime для beat tracking без доказанной необходимости.

Каждую native dependency проверить на arm64-v8a и armeabi-v7a.

## 27. Лицензии

Проект вдохновлён build/tooling подходом `einhander/piano`, но scaffold написан отдельно и не требует FluidSynth.

Если агент начнёт копировать исходный код из сторонних проектов, он обязан проверить лицензию и добавить необходимые notices. Не переносить GPL-код из `piano` механически, если будущая лицензия TempoScore ещё не определена.

Oboe распространяется по Apache-2.0; сохраняй требуемые notices при дистрибуции.

## 28. Coding style

### Kotlin

- небольшие classes/files по ответственности;
- никаких DSP loops в Activity;
- file parsing не на main thread;
- UI state обновлять на main thread;
- не скрывать ошибки empty catch.

### C++

- C++17;
- RAII вне RT;
- `noexcept` для RT helpers где уместно;
- fixed/preallocated storage в RT;
- atomics с минимально достаточным memory order;
- избегать shared ownership в callback logic;
- комментарии объясняют real-time reason, а не очевидный синтаксис.

## 29. Работа с текущим bootstrap BeatTracker

Не полируй его бесконечно.

Он существует, чтобы проверить:

```text
mic -> native callback -> beat observation -> transport -> JNI -> UI
```

После подтверждения pipeline создай новую production implementation, желательно с интерфейсом:

```cpp
class IBeatTracker {
public:
    virtual void setPrior(...) = 0;
    virtual BeatObservation latest() const = 0;
};
```

или аналогичным без virtual в RT path.

Старый energy tracker можно оставить как fallback/debug backend.

## 30. Рекомендуемый milestone plan

### M0 — baseline

- build debug;
- unit tests;
- запуск на Android 10;
- load MIDI;
- Oboe permission/start/stop.

### M1 — transport correctness

- tempo map helper;
- bar/beat tests;
- start from bar;
- transport independent of UI timers.

### M2 — native analysis architecture

- SPSC ring;
- analyzer thread;
- state handoff;
- diagnostics;
- no heavy work callback.

### M3 — onset detector

- FFT;
- multiband spectral flux;
- offline plots/metrics;
- adaptive peak picking.

### M4 — tempo tracker

- prior;
- half/double handling;
- continuity;
- confidence.

### M5 — phase PLL

- beat phase;
- bounded corrections;
- reacquisition;
- syncopation robustness.

### M6 — real music validation

- drum tracks;
- bass+guitar;
- ensemble;
- rubato/light tempo drift;
- long run.

### M7 — UI

- fixed playhead scrolling;
- easy start-bar selector;
- tracking state;
- landscape UX.

### M8 — notation

- quantized notation model;
- staff rendering;
- clefs/accidentals/rests/chords;
- only after M0–M7 stable.

## 31. Definition of done for each change

Перед завершением задачи агент должен:

1. собрать соответствующий target;
2. запустить существующие tests;
3. добавить test для исправленной pure logic где возможно;
4. проверить, что не добавил main-thread I/O/DSP;
5. проверить RT callback на allocation/lock/log/JNI;
6. обновить docs, если изменён архитектурный контракт;
7. перечислить известные ограничения, а не маскировать их.

## 32. Что считать регрессией

- targetSdk изменён с 29 без прямого требования;
- MIDI keyboard внезапно стал нужен;
- микрофонный PCM пошёл через Kotlin;
- transport основан на UI timer;
- detected BPM напрямую присваивается transport BPM без smoothing/state model;
- тяжелый FFT выполняется в callback;
- отдельный поток обновляет position по wall clock параллельно audio callback;
- UI перестаёт показывать будущее относительно playhead;
- абсолютная позиция «угадывается» из одного BPM;
- bootstrap energy detector назван production-ready.

## 33. Первый рекомендуемый task для агента

После успешной сборки не начинай с нотной графики.

Сделай **M1 + начало M2**:

1. добавить `tempoAtQuarterBeat()` и tests;
2. добавить выбор стартового такта;
3. добавить native diagnostics (`sampleRate`, frames/burst, AudioApi);
4. добавить preallocated SPSC ring + host tests;
5. вынести будущую analyzer boundary;
6. сохранить текущий energy tracker как временный backend;
7. убедиться, что приложение продолжает показывать позицию/Now/Next.

Это создаст правильный фундамент для реального beat tracker.
