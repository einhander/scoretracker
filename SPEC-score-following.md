# TempoScore — спецификация

## 1. Назначение

TempoScore — Android-приложение для репетиций и игры по заранее известному MIDI. Пользователь загружает `.mid/.midi`, приложение показывает текущую область партитуры и ближайший музыкальный материал, а микрофон используется для того, чтобы во время живой игры автоматически понимать:

1. с каким темпом сейчас играет музыкант/ансамбль;
2. где примерно в загруженном произведении сейчас находится живое исполнение;
3. насколько уверенно приложение знает эту позицию.

Целевая платформа: **Android 10 / API 29**.

Главный пользовательский сценарий новой версии:

```text
Load MIDI
   -> начать играть с произвольного места
   -> нажать Listen
   -> приложение слушает примерно 8–10 секунд
   -> находит соответствующее место MIDI
   -> переводит отображение к найденной позиции
   -> дальше непрерывно ведёт позицию по tempo/beat tracker
   -> каждые ~2 секунды перепроверяет последние ~10 секунд и мягко исправляет drift
```

Это называется **online score following / score-position tracking**.

## 2. Что приложение НЕ делает

Приложение не должно превращаться в транскриптор живого аудио.

Не требуется:

- MIDI-клавиатура;
- Android MIDI input;
- audio-to-MIDI;
- распознавание отдельных сыгранных нот вида `F#4`;
- chord recognition как пользовательская функция;
- выделение отдельного инструмента из смеси;
- нейросетевой ASR/ML backend;
- сервер или облако.

При этом для определения позиции **разрешено и требуется** извлекать из аудио грубые музыкальные признаки:

- 12-bin chroma / pitch-class energy;
- onset strength / spectral flux;
- ритмические признаки;
- BPM/beat phase.

То есть приложение не говорит «сыграна нота C4», но может знать, что в данном окне сильна pitch class `C`.

## 3. Жёсткие продуктовые ограничения

1. Live input — микрофон Android-устройства/выбранный системный input.
2. MIDI является эталонной партитурой и единственным источником отображаемых нот.
3. Всё score following работает локально на телефоне.
4. Базовый/ожидаемый BPM берётся из MIDI tempo map.
5. `targetSdk = 29` сохраняется без отдельного решения.
6. C++/NDK/CMake/Oboe остаются основой production audio/DSP path.
7. Тяжёлый DSP и поиск позиции нельзя выполнять в Oboe callback.
8. Никакой обязательной записи микрофона на диск.

## 4. Пользовательский сценарий

### 4.1 Загрузка

1. Пользователь нажимает `Load MIDI`.
2. Выбирает SMF 0/1 через Storage Access Framework.
3. Приложение читает:
   - PPQ;
   - Note On / Note Off;
   - tempo map;
   - time signatures;
   - tracks/channels;
   - общую длину.
4. Из MIDI один раз строится **score reference template** для position matcher.

### 4.2 Listen с произвольного места

Пользователь может начать играть не с начала произведения.

После `Listen`:

```text
IDLE -> ACQUIRING_GLOBAL
```

Приложение набирает rolling window живого звука. Целевой размер окна:

```text
8–10 s
```

После накопления достаточного сигнала выполняется global search по всей композиции.

Если найдено уникальное совпадение с достаточной confidence:

```text
ACQUIRING_GLOBAL -> LOCKED
```

Транспорт переносится к найденной musical position, и UI показывает соответствующее место партитуры.

### 4.3 Работа после lock

После первичного определения позиции:

- `BeatTracker` постоянно оценивает live tempo и beat phase;
- `LiveTransport` плавно двигает playhead между проверками;
- `PositionMatcher` использует rolling window последних ~10 секунд;
- новый match запускается ориентировочно каждые **2 секунды**;
- после lock поиск сначала ограничен областью около predicted position;
- найденная абсолютная позиция корректирует drift транспорта.

Таким образом 10 секунд — это прежде всего **длина контекстного окна**, а не период, в течение которого playhead стоит на месте.

## 5. Внутренняя координата позиции

Основная musical coordinate:

```text
quarterBeatPosition = число четвертных долей от начала MIDI
```

Пример для 4/4:

```text
0.00 = bar 1 beat 1
1.00 = bar 1 beat 2
4.00 = bar 2 beat 1
```

Time signature map переводит эту координату в `bar + notated beat`.

Между absolute position observations:

```text
dPosition/dt = transportBpm / 60
```

Position matcher периодически выдаёт независимое наблюдение абсолютной позиции.

## 6. Два независимых контура tracking

Архитектурно нельзя смешивать tempo tracking и score localization в один непрозрачный алгоритм.

### 6.1 Fast loop: tempo/phase

Работает непрерывно:

```text
microphone
 -> onset/beat analysis
 -> detected BPM + beat phase
 -> LiveTransport
```

Назначение:

- плавное движение playhead;
- быстрая реакция на accelerando/ritardando;
- отсутствие ступенчатого UI.

### 6.2 Slow loop: absolute position

Работает на sliding context:

```text
microphone
 -> STFT
 -> chroma + onset/flux features
 -> rolling feature window ~10 s
 -> score matcher / constrained DTW
 -> PositionObservation
```

Назначение:

- определить старт с произвольного места;
- исправлять накопленный drift;
- восстанавливаться после потери tracking;
- распознавать переход на другую часть произведения, если evidence достаточно уникален.

## 7. Score reference template из MIDI

Reference строится один раз после загрузки MIDI.

### 7.1 Harmonic channel

Для pitched MIDI notes:

```text
pitchClass = pitch % 12
```

Создаётся нормализованный 12-dimensional chroma reference.

Рекомендуемое начальное weighting:

- note velocity влияет умеренно;
- активные ноты дают sustain contribution;
- начало ноты может иметь дополнительный небольшой accent;
- MIDI percussion channel 10 (zero-based channel `9`) не участвует в chroma.

### 7.2 Rhythmic channel

Из Note On строится onset/reference envelope.

Для percussion track/channel:

- pitches не используются как harmonic chroma;
- Note On участвуют в onset/rhythm reference.

### 7.3 Reference time axis

Каждый reference frame должен однозначно отображаться обратно в:

```text
quarterBeatPosition
```

Допустимые реализации:

1. nominal-time grid, построенный с учётом MIDI tempo map;
2. beat-domain grid с явным tempo/slope constraint в matcher.

Для первой реализации предпочтителен **nominal-time reference grid** с сохранённым `quarterBeatPosition` для каждого frame: он проще для global search и отладки.

Стартовый reference rate после агрегации:

```text
10 Hz
```

Допустимо экспериментировать с 5–20 Hz после профилирования.

## 8. Live audio features

### 8.1 Input

- Oboe;
- mono;
- float PCM;
- actual stream sample rate;
- `InputPreset::Unprocessed`, fallback `VoiceRecognition`;
- low latency stream;
- никаких предположений, что sample rate всегда 48 kHz.

### 8.2 STFT

Стартовые параметры:

```text
FFT: 2048 или 4096
hop: 512 или 1024
window: Hann
```

Для chroma предпочтительно начать с 4096 samples при 44.1/48 kHz, потому что слишком короткое окно ухудшает низкочастотное разрешение.

### 8.3 Chroma

Из magnitude spectrum получить 12 pitch classes.

Начальная реализация может:

1. игнорировать DC и очень низкие частоты;
2. использовать диапазон примерно 55 Hz .. 5 kHz;
3. перевести частоту bin в MIDI pitch:

```text
p = 69 + 12 * log2(f / 440)
```

4. накопить энергию в `round(p) mod 12` или использовать линейное распределение между соседними pitch-class bins;
5. применить log compression;
6. L2/L1 normalize frame;
7. low-energy frames помечать как weak/silence.

Это coarse harmonic feature, а не note recognition.

### 8.4 Onset / flux

Из той же STFT считать positive spectral flux, желательно multiband:

```text
low:  ~40..220 Hz
mid:  ~220..2000 Hz
high: ~2..10 kHz
```

Для position matcher можно использовать один нормализованный onset channel или 2–3 band channels.

### 8.5 Feature frame

Минимальная первая версия:

```text
LiveFeatureFrame:
  chroma[12]
  onset
  energy/validity
```

Итого matcher использует 13 meaningful dimensions плюс validity.

## 9. Sliding window

Нужно хранить последние live feature frames, а не сырой PCM для всего произведения.

Начальные параметры:

```text
window target     = 10.0 s
minimum acquire   = 6.0 s
match hop         = 2.0 s
feature rate      = 10 Hz
```

То есть типичное окно matcher содержит около 100 feature frames.

При слабом/тихом сигнале global lock можно отложить до появления достаточного количества valid frames.

## 10. Position matching

### 10.1 Почему не обычная correlation

Живое исполнение может быть быстрее/медленнее MIDI и локально менять темп. Поэтому прямое frame-to-frame сравнение недостаточно.

Начальный production алгоритм:

```text
coarse candidate search
 -> constrained DTW для лучших кандидатов
 -> ranking
 -> confidence/ambiguity estimation
```

Neural network не требуется.

### 10.2 Distance между feature frames

Рекомендуемая базовая стоимость:

```text
harmonicDistance = 1 - cosine(liveChroma, scoreChroma)
onsetDistance    = abs(liveOnset - scoreOnset)

frameCost = 0.75 * harmonicDistance
          + 0.25 * onsetDistance
```

Весы являются стартовыми tuning constants, а не API contract.

На участках без tonal content onset может получать больший вес.

### 10.3 Constrained DTW

DTW должен разрешать умеренные tempo differences, но запрещать абсурдные warp paths.

Начальная допустимая скорость:

```text
0.65x .. 1.50x nominal
```

При наличии стабильного live BPM диапазон следует сузить вокруг отношения:

```text
liveBpm / midiBpm(candidate)
```

Использовать Sakoe-Chiba-like band / slope constraints.

### 10.4 Global search

Используется:

- сразу после `Listen`;
- при явной потере lock;
- после ручного запроса reacquire.

Global search рассматривает всю композицию, но не должен запускать полный дорогой DTW в каждой точке.

Первая реализация должна быть hierarchical:

1. score reference заранее decimated/precomputed;
2. быстрым coarse metric получить top-K candidates;
3. только top-K проверить DTW;
4. выбрать best candidate и second-best independent candidate.

Ориентир:

```text
coarse step: 0.5–1.0 s nominal score time
top K:       5–12
```

### 10.5 Local search

После lock ожидаемая позиция уже известна.

Начальное окно:

```text
predicted position ± 30 s
```

Если confidence падает, расширять:

```text
±30 s -> ±60 s -> global
```

Local search должен учитывать continuity prior.

## 11. Повторяющиеся части и ambiguity

Куплеты/припевы могут быть почти идентичны.

Нельзя считать `bestScore` достаточным.

Matcher обязан учитывать:

1. absolute match quality;
2. разницу между best и second-best **не соседним** кандидатом;
3. continuity с predicted position;
4. стабильность результата нескольких последовательных match windows.

Пример:

```text
01:12  quality 0.91
02:47  quality 0.90
```

При initial global acquisition это **ambiguous**, а не уверенный lock.

Если transport уже ожидал ~02:45, continuity prior делает 02:47 предпочтительным.

При ambiguous acquisition приложение продолжает слушать более длинный контекст вместо случайного прыжка.

## 12. PositionObservation

Native matcher должен публиковать структуру примерно такого смысла:

```cpp
struct PositionObservation {
    double quarterBeatPosition;
    double positionErrorBeats;
    float matchQuality;
    float confidence;
    float ambiguityMargin;
    bool valid;
    bool globalMatch;
};
```

Точные поля можно менять, но наружу обязательно должны быть доступны:

- matched musical position;
- confidence;
- состояние lock/acquire/lost;
- величина текущего position error для диагностики.

## 13. Состояния score following

Production state machine:

```text
IDLE
  |
  v
ACQUIRING_GLOBAL
  | good unique match
  v
LOCKED_LOCAL
  | weak/ambiguous
  v
WEAK
  | prolonged failure
  v
REACQUIRING
  | global unique match
  +-----------------> LOCKED_LOCAL
```

Допустимо хранить enum:

```text
IDLE = 0
ACQUIRING = 1
LOCKED = 2
WEAK = 3
REACQUIRING = 4
```

## 14. Как absolute match корректирует LiveTransport

Нельзя безусловно делать `resetPosition()` после каждого match.

Пусть:

```text
error = matchedPosition - predictedPosition
```

### 14.1 Small error

```text
|error| < 0.5 beat
```

Использовать как мягкую phase/position correction. Видимого прыжка быть не должно.

### 14.2 Medium error

```text
0.5 .. 4 beats
```

Использовать slew correction: временно добавить ограниченную correction velocity к transport, чтобы за несколько секунд убрать drift.

Пример ограничения:

```text
max correction speed ~0.25..0.50 beat/s
```

### 14.3 Large error

```text
> 4 beats
```

Hard relocation допускается только если:

- confidence высокая;
- ambiguity margin достаточная;
- match стабилен минимум в двух последовательных проверках либо confidence очень высокая при initial global lock.

При initial `ACQUIRING_GLOBAL` найденную позицию можно применить сразу, потому что до lock transport ещё не считается надёжным.

## 15. BeatTracker и PositionMatcher — разные confidence

Нужно разделить:

```text
beatConfidence
positionConfidence
```

`beatConfidence` отвечает за качество tempo/phase.

`positionConfidence` отвечает за уверенность, что найдено правильное место композиции.

UI не должен показывать одно число `Confidence`, смешивающее два разных понятия.

## 16. Tempo map

MIDI может содержать несколько tempo events.

Нужны функции:

```text
tempoAtQuarterBeat(position)
quarterBeatToSeconds(position)
secondsToQuarterBeat(seconds)
```

`expectedBpm` native beat tracker должен обновляться при переходе MIDI tempo region.

Живой tempo scale предпочтительно хранить относительно MIDI:

```text
midi 120, live 114 => scale 0.95
next MIDI tempo 140 => expected live ~133
```

## 17. Native threading model

Целевая архитектура:

```text
                    +---------------- BeatTracker ----------------+
                    |                                              |
Oboe RT callback ---+--> copy PCM to preallocated SPSC ring        |
                    |                                              v
                    +--> LiveTransport <--- latest beat observation
                             ^
                             |
                             | latest position observation
                             |
                    Analyzer / Matcher thread
                    - STFT
                    - spectral flux
                    - chroma
                    - rolling live features
                    - tempo/beat analysis if moved here
                    - global/local position matching
```

Допустим один analyzer thread для первой реализации.

Если beat analysis и position matching начинают мешать друг другу по latency, позже разделить их, но не преждевременно.

## 18. Hard real-time rules

`onAudioReady()` не должен выполнять:

- FFT/DTW;
- allocation/deallocation;
- mutex;
- blocking queue;
- condition_variable wait;
- logging;
- JNI;
- Java/Kotlin;
- file/network I/O;
- создание потока;
- resizing STL containers.

Callback делает только bounded work:

1. минимальный meter/RMS при необходимости;
2. копирование в preallocated SPSC ring;
3. чтение atomic/seqlock observations;
4. sample-frame advance `LiveTransport`;
5. публикация small transport state.

## 19. JNI/API boundary

JNI остаётся узким.

Нужны операции уровня:

```text
initialize(expectedBpm, startBeat)
setScoreReference(... one-time MIDI/reference data ...)
start()
stop()
setExpectedBpm(...)
resetPosition(...)
requestGlobalReacquire()
getState()
```

Не передавать каждый PCM block через JNI.

Не делать JNI callback из audio thread.

Передача MIDI/reference data выполняется один раз при `Load MIDI`, поэтому обычные JNI arrays допустимы.

## 20. Рекомендуемая структура native-кода

```text
app/src/main/cpp/
  audio/
    OboeInputEngine.h/.cpp
    SpscAudioRing.h/.cpp

  dsp/
    Stft.h/.cpp
    ChromaExtractor.h/.cpp
    SpectralFlux.h/.cpp

  beat/
    BeatTracker.h/.cpp

  position/
    ScoreReference.h/.cpp
    FeatureRing.h/.cpp
    DtwMatcher.h/.cpp
    PositionMatcher.h/.cpp

  transport/
    LiveTransport.h/.cpp

  native_audio_jni.cpp
```

Имена можно немного менять, но разделение ответственности сохранять.

## 21. Kotlin responsibilities

Kotlin отвечает за:

- Android lifecycle;
- permissions;
- Storage Access Framework;
- MIDI parser/model;
- tempo/time-signature helpers;
- передачу score reference data в native;
- отображение;
- polling native state;
- UI state/diagnostics.

Kotlin не занимается live FFT/DTW.

## 22. UI

### 22.1 Основные состояния

До lock:

```text
Listening…
Locating position  7.2 / 10 s
```

При неоднозначности:

```text
Listening…
Position ambiguous — collecting more context
```

После lock:

```text
LIVE 117.4 BPM
Bar 42 Beat 3.20
Position LOCKED 91%
Beat 78%
```

При восстановлении:

```text
Position weak — reacquiring…
```

### 22.2 Поведение score view

- playhead фиксирован примерно на 30–40% ширины;
- score content движется относительно него;
- между matcher updates движение плавное;
- hard jump разрешён при первоначальном global lock или уверенном relocation;
- небольшой drift никогда не должен выглядеть как дёрганье.

## 23. Track selection и matching source

UI track selection для отображения и reference для matching — разные вещи.

Первая версия matcher использует **все MIDI tracks**, потому что микрофон может слышать весь ансамбль.

Правила:

- pitched tracks -> chroma + onset;
- percussion channel -> onset only;
- muted/display-selected track не должен автоматически менять matcher reference.

Позже можно добавить настройку `Tracks used for matching`.

## 24. Тишина и остановка исполнения

Тишина не означает автоматически конец произведения.

Политика:

- short silence -> transport продолжает prediction;
- beat confidence падает;
- position matcher не принимает слабое окно как новый location;
- prolonged weak evidence -> `WEAK/REACQUIRING`;
- freeze-on-stop может быть отдельной пользовательской настройкой.

## 25. Privacy

- live audio не отправляется в сеть;
- по умолчанию не сохраняется на диск;
- rolling raw PCM buffer держит только короткий технический интервал;
- debug recording допускается только явным developer option.

## 26. Performance targets

Для Android 10-class device:

- UI 30–60 FPS;
- Oboe callback lock-free/allocation-free;
- continuous audio без блокировок со стороны matcher;
- live feature rate после aggregation около 10 Hz;
- local match каждые ~2 s;
- initial position lock обычно после 6–12 s музыкально информативного материала;
- local score following correction latency 2–4 s;
- average DSP желательно укладывать примерно в один mobile CPU core с запасом;
- никаких сетевых зависимостей.

Цифры — engineering targets до реального профилирования.

## 27. Acceptance criteria — Milestone A: infrastructure

- MIDI по-прежнему загружается и отображается;
- Oboe input работает на Android 10;
- callback не содержит FFT/DTW/locks/allocation;
- PCM поступает в SPSC ring;
- analyzer thread стабильно получает данные;
- native state публикуется без crash/race;
- старый tempo transport не сломан.

## 28. Acceptance criteria — Milestone B: offline matcher

До live integration должен существовать тестовый/offline matcher.

Минимум тестов:

1. MIDI reference matched against synthetic/reference-like chroma at correct position;
2. live sequence stretched to 0.8x/1.2x still matches correct region;
3. wrong region has lower score;
4. repeated regions produce low ambiguity margin;
5. local search prefers continuity-consistent candidate;
6. silence/invalid window does not create confident match.

## 29. Acceptance criteria — Milestone C: live global acquisition

Сценарий:

1. загрузить MIDI;
2. начать проигрывать/играть с места не в начале;
3. нажать `Listen`;
4. приложение набирает примерно 8–10 s;
5. при уникальном материале автоматически переходит к правильной области MIDI.

Цель первой версии:

```text
ошибка после lock <= примерно 1–2 музыкальных доли
```

на чистом тестовом материале/записи, соответствующей MIDI.

Не требовать такой точности на любом акустическом ансамбле до реального dataset/tuning.

## 30. Acceptance criteria — Milestone D: continuous following

После lock:

- transport плавно следует live BPM;
- matcher проверяет rolling context каждые ~2 s;
- при умеренном drift позиция плавно возвращается;
- повторяющийся припев не вызывает случайный прыжок назад;
- короткая пауза не сбрасывает lock;
- если исполнитель перескочил на достаточно уникальный другой раздел, приложение после reacquire способно переместиться туда;
- UI различает beat confidence и position confidence.

## 31. Порядок разработки

1. Зафиксировать regression baseline существующей сборки.
2. Добавить SPSC ring + analyzer thread без изменения DSP результата.
3. Добавить STFT.
4. Добавить live chroma + spectral flux и debug metrics.
5. Добавить score reference builder из MIDI.
6. Добавить offline frame distance + constrained DTW tests.
7. Реализовать global candidate search.
8. Реализовать local search + continuity prior.
9. Добавить `PositionObservation` и state machine.
10. Интегрировать corrections в `LiveTransport`.
11. Добавить UI `ACQUIRING/LOCKED/WEAK/REACQUIRING`.
12. Тестировать сначала на воспроизводимом аудио/рендере MIDI, затем на реальном микрофоне и ансамбле.
13. Только после устойчивого score following заниматься полноценным notation engraving.

## 32. Не делать без отдельного решения

- не добавлять FluidSynth в production path только ради matching;
- не добавлять MIDI controller input;
- не делать full audio transcription;
- не использовать cloud recognition;
- не выполнять DTW в Oboe callback;
- не привязывать musical time к Android `Handler/Timer`;
- не заменять Oboe на Java AudioRecord как основной backend;
- не считать один highest correlation достаточным для repeated sections;
- не делать hard `resetPosition()` на каждом matcher result;
- не считать текущий bootstrap `BeatTracker` production-grade.
