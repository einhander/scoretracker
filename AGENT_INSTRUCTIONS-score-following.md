# TempoScore — инструкция coding agent: live score-position tracking

## 0. Цель задачи

Доработать существующий Android-проект так, чтобы после загрузки MIDI пользователь мог начать играть **с произвольного места**, нажать `Listen`, и приложение примерно по последним **8–10 секундам** звука определяло место в MIDI, переводило туда отображаемую партитуру и дальше удерживало синхронизацию во время живой игры.

Нужен **online score following**, а не только BPM tracking.

Система должна сочетать:

```text
fast loop:  beat/BPM/phase -> плавный LiveTransport
slow loop:  chroma+onset ~10 s -> absolute PositionMatcher
```

## 1. Перед изменениями

Прочитай полностью:

1. `SPEC.md` — главный источник продуктовых требований;
2. `README.md`;
3. `docs/architecture.md`;
4. `docs/realtime-rules.md`;
5. текущие реализации:
   - `app/src/main/cpp/audio/OboeInputEngine.*`
   - `app/src/main/cpp/beat/BeatTracker.*`
   - `app/src/main/cpp/transport/LiveTransport.*`
   - `app/src/main/cpp/native_audio_jni.cpp`
   - `NativeAudioBridge.kt`
   - `MidiModels.kt`
   - `MidiFileParser.kt`
   - `ScoreNavigator.kt`
   - `MainActivity.kt`

Если старые документы утверждают, что из live audio разрешён только rhythm/BPM, для этой задачи это устаревшее ограничение. **`SPEC.md` в этой ветке имеет приоритет:** coarse chroma/pitch-class features разрешены, но audio-to-MIDI по-прежнему запрещён.

## 2. Текущий baseline, который нельзя ломать

Существующий код уже имеет:

```text
Kotlin MIDI parser
ScoreNavigator
ScoreStaffView
NativeAudioBridge
OboeInputEngine
BeatTracker bootstrap
LiveTransport
JNI state polling
```

Текущий поток:

```text
Oboe callback
 -> BeatTracker::process()
 -> LiveTransport::processFrames()
 -> atomic published state
 -> JNI getStateRaw()
 -> MainActivity/ScoreStaffView
```

Не переписывай проект с нуля.

Сначала сохрани работающие функции:

- `Load MIDI`;
- выбор track для отображения;
- `Listen/Stop`;
- `Reset`;
- показ bar/beat;
- текущий smooth transport.

## 3. Build baseline

Сохранять:

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
Oboe       Prefab dependency
```

Перед архитектурными изменениями:

```bash
cp local.properties.template local.properties
# выставить sdk.dir

./build.sh debug
./build.sh test
```

Если environment не позволяет Android build, всё равно добавляй host-testable C++ units там, где это возможно, и явно фиксируй, что реально проверено.

## 4. Неподлежащие переинтерпретации требования

Не добавляй:

- MIDI keyboard/controller input;
- audio-to-MIDI;
- пользовательское распознавание отдельных нот;
- chord recognizer;
- cloud backend;
- FluidSynth как обязательный matcher dependency;
- neural network в первой реализации.

Разрешено/требуется извлекать:

```text
12-bin chroma
spectral flux/onset
BPM
beat phase
```

Это внутренние features для localization, а не transcription.

## 5. Основная архитектура после доработки

Цель:

```text
                         +-------------------------------+
                         |       analyzer thread         |
                         |                               |
Oboe RT callback ----+-->| SPSC -> STFT -> chroma/flux  |
                     |   |            |                  |
                     |   |            +-> BeatTracker    |
                     |   |            |                  |
                     |   |            +-> rolling ~10 s  |
                     |   |                PositionMatcher|
                     |   +------------------+------------+
                     |                      |
                     |       atomic latest observations
                     |                      |
                     +----> LiveTransport <-+
                                |
                                v
                         atomic TransportState
                                |
                                v
                              JNI/UI
```

Допустимо на первой итерации оставить bootstrap `BeatTracker` в callback, пока SPSC/analyzer инфраструктура вводится отдельным commit. Но итоговый production path должен убрать тяжёлый beat DSP из callback и не добавлять туда STFT/DTW.

## 6. Реализовывать по маленьким проверяемым этапам

Не делай один giant commit.

Рекомендуемые commits:

```text
1. Add lock-free audio ring and analyzer lifecycle
2. Add STFT/chroma/flux feature extraction
3. Add MIDI score reference model
4. Add DTW matcher with host tests
5. Add global/local position matcher state machine
6. Integrate position corrections into transport
7. Extend JNI/native state
8. Add locating/locked/reacquiring UI
9. Tune and document thresholds
```

Каждый commit должен собираться сам по себе, если tooling доступен.

## 7. Этап 1 — SPSC audio ring

Добавь примерно:

```text
app/src/main/cpp/audio/SpscAudioRing.h
app/src/main/cpp/audio/SpscAudioRing.cpp   # если нужен
```

Producer: `OboeInputEngine::onAudioReady()`.

Consumer: analyzer worker.

Требования:

- preallocated storage;
- single producer/single consumer;
- producer не блокируется;
- никаких mutex в callback;
- никаких allocations после start;
- mono float samples;
- overflow counter;
- явная политика overflow: drop oldest или drop incoming block, но never block.

Предпочтительно ring capacity примерно 2–4 секунды PCM, потому что worker должен успеть догнать callback, но это **не** 10-second matcher window. Matcher хранит уже decimated features.

Добавь host unit tests:

- write/read order;
- wrap-around;
- overflow policy;
- empty/full behavior.

## 8. Analyzer thread lifecycle

Добавь worker ownership в `OboeInputEngine` или отдельный `AudioAnalyzer`.

Lifecycle:

```text
initialize/load score
start():
  prepare buffers
  start analyzer
  open/start Oboe

stop():
  stop Oboe
  signal analyzer stop
  join analyzer from non-RT thread
```

Нельзя делать `join()` из audio callback/error callback.

Analyzer может sleep/yield/wait на своей стороне. Callback никогда его не ждёт.

## 9. Этап 2 — STFT

Добавь:

```text
app/src/main/cpp/dsp/Stft.h/.cpp
```

Не тащи огромный DSP framework без необходимости.

Можно использовать лёгкую FFT implementation, совместимую с лицензией проекта, либо собственный минимальный wrapper над уже допустимой dependency. Если добавляешь third-party FFT:

- зафиксируй license;
- не делай network download во время app runtime;
- CMake build должен быть воспроизводимым.

Стартовые параметры:

```text
FFT size = 4096
hop      = 1024
window   = Hann
```

Actual sample rate приходит от Oboe.

API должно позволять позже сменить size/hop без переписывания matcher.

## 10. Этап 3 — ChromaExtractor

Добавь:

```text
app/src/main/cpp/dsp/ChromaExtractor.h/.cpp
```

Минимальный output:

```cpp
struct AudioFeatureFrame {
    std::array<float, 12> chroma;
    float onset;
    float energy;
    bool valid;
    int64_t centerAudioFrame;
};
```

Алгоритм первой версии:

1. magnitude/power spectrum;
2. frequency range примерно `55 Hz .. 5 kHz`;
3. bin frequency -> fractional MIDI pitch:

```text
69 + 12 * log2(f / 440)
```

4. fold to 12 pitch classes;
5. log compression (`log1p(k*x)` либо sqrt);
6. normalize chroma;
7. если RMS/energy ниже noise gate, `valid=false`.

Не возвращай explicit detected pitch/name.

## 11. Этап 4 — SpectralFlux

Добавь:

```text
app/src/main/cpp/dsp/SpectralFlux.h/.cpp
```

Минимум:

```text
positive flux = sum(max(0, mag[t] - mag[t-1]))
```

Желательно multiband normalization:

```text
low  ~40..220
mid  ~220..2000
high ~2000..10000
```

Для `AudioFeatureFrame.onset` сначала можно смешать bands в одно значение.

Важно: этот onset feature нужен не только BeatTracker, но и absolute matcher.

## 12. Feature rate и rolling feature ring

STFT frames слишком частые для DTW.

Агрегируй до ориентировочно:

```text
10 feature frames / second
```

Добавь fixed-capacity rolling container примерно на 15–20 секунд features.

Matcher target window:

```text
10 s
```

Minimum acquisition:

```text
6 s valid-enough data
```

Match cadence:

```text
~2 s
```

Cadence определяется analyzer/sample-frame time, а не Android UI timer.

## 13. Этап 5 — score reference из MIDI

Не пиши второй MIDI parser в C++.

Текущий `MidiFileParser.kt` остаётся единственным SMF parser.

Есть два допустимых варианта передачи данных. Для первой реализации используй **вариант A**.

### Variant A — передать compact MIDI arrays в native один раз

Добавь в `NativeAudioBridge` one-time метод по смыслу:

```kotlin
external fun setScoreReference(
    ppq: Int,
    totalTicks: Long,
    noteChannels: IntArray,
    notePitches: IntArray,
    noteVelocities: IntArray,
    noteStartTicks: LongArray,
    noteEndTicks: LongArray,
    tempoTicks: LongArray,
    tempoUsPerQuarter: IntArray,
)
```

Можно слегка изменить signature для удобства JNI, но не передавать notes по одному вызову.

В C++ добавить:

```text
app/src/main/cpp/position/ScoreReference.h/.cpp
```

Он строит reference frames и хранит mapping каждого frame -> quarterBeat.

### Variant B — reference precompute в Kotlin

Допустим только если Variant A чрезмерно усложняет JNI. Kotlin может построить MIDI-only reference и передать flat FloatArray, потому что это не live audio DSP. Но тогда feature definition должна быть документирована и синхронизирована с C++.

## 14. ScoreReference rules

Для каждого reference frame:

```cpp
struct ScoreFeatureFrame {
    std::array<float, 12> chroma;
    float onset;
    double quarterBeatPosition;
    double nominalSeconds;
};
```

Правила:

- pitched note contributes to `pitch % 12` while active;
- velocity weight clamp/compress, например `sqrt(velocity/127)`;
- onset accent на frame, где note starts;
- MIDI channel 9 (General MIDI percussion channel 10) **не** contributes to chroma;
- percussion Note On contributes to onset;
- normalize chroma frame;
- reference time grid = 10 Hz nominal MIDI time для первой реализации.

Нужны helpers tempo map:

```text
tick -> quarter beat
quarter beat -> nominal seconds
nominal seconds -> quarter beat
midi BPM at quarter beat
```

Tempo events могут быть не только в tick 0.

## 15. Не путай display track selection с matching tracks

`TrackSelection` в UI сейчас определяет, что показывается.

Matcher MVP использует **все MIDI tracks**.

Не делай так:

```text
user selected Piano track for display
=> matcher suddenly ignores drums/bass/guitar
```

Это отдельная будущая настройка.

## 16. Этап 6 — frame distance

Добавь:

```text
app/src/main/cpp/position/DtwMatcher.h/.cpp
```

Начальная cost function:

```text
chromaDistance = 1 - cosine(live.chroma, score.chroma)
onsetDistance  = abs(live.onset - score.onset)

cost = 0.75 * chromaDistance
     + 0.25 * onsetDistance
```

Если live frame invalid/silent:

- не считай его сильным evidence;
- либо пропускай/понижай weight;
- нельзя превращать silence-silence в высокий confident musical match.

## 17. Constrained DTW

Требования:

- bounded memory;
- reuse allocated vectors;
- no allocation inside inner per-candidate loop;
- slope/warp constraint;
- normalize final cost по effective path length;
- возвращать quality, который можно сравнивать между candidates.

Начальный tempo warp:

```text
0.65x .. 1.50x nominal
```

Если есть устойчивый detected/live BPM, сузь band вокруг expected ratio.

Не делай unrestricted DTW: он сможет искусственно «объяснить» неправильный кусок слишком сильным warp.

## 18. Обязательные DTW host tests

Создай deterministic synthetic tests без Android microphone.

Минимум:

### Test 1 — exact

Reference fragment должен найти сам себя.

### Test 2 — tempo stretch

Сгенерируй live features из reference с resampling 0.8x и 1.2x. Правильная candidate position остаётся лучшей.

### Test 3 — wrong section

Другой harmonic progression должен иметь заметно худший normalized cost.

### Test 4 — repeated section

Два одинаковых фрагмента должны дать близкие scores и низкую ambiguity margin.

### Test 5 — continuity

При predicted position около второго повторения local matcher должен предпочесть второе повторение.

### Test 6 — silence

Invalid/silent live window не должен выдавать `confidence > lockThreshold`.

## 19. Этап 7 — PositionMatcher

Добавь:

```text
app/src/main/cpp/position/PositionMatcher.h/.cpp
```

State примерно:

```cpp
enum class PositionTrackingState {
    Idle,
    Acquiring,
    Locked,
    Weak,
    Reacquiring,
};
```

Observation:

```cpp
struct PositionObservation {
    double quarterBeatPosition = 0.0;
    double positionErrorBeats = 0.0;
    float matchQuality = 0.0f;
    float confidence = 0.0f;
    float ambiguityMargin = 0.0f;
    bool valid = false;
    bool globalMatch = false;
};
```

## 20. Global acquisition algorithm

После `Listen`:

```text
state = Acquiring
```

Пока valid context < 6 s:

```text
не пытаться lock
```

После 6–10 s:

### Coarse pass

По всей score reference timeline с шагом примерно 0.5–1.0 nominal second вычислить дешёвый similarity.

Можно использовать:

- decimated chroma sequence correlation;
- window-mean chroma + onset profile;
- короткий banded distance.

Цель coarse pass — только получить top-K.

Не делай final lock по window-mean chroma: одинаковая гармония встречается слишком часто.

### Fine pass

Запустить constrained DTW для top-K candidates, например:

```text
K = 8
```

После ranking получить:

```text
best candidate
second-best non-neighbour candidate
match quality
ambiguity margin
```

`non-neighbour` означает не считать соседние timestamps вокруг того же physical match отдельной альтернативой.

## 21. Confidence и lock policy

Не hardcode единственное число без diagnostics.

Confidence должна зависеть хотя бы от:

```text
absolute normalized DTW quality
best-vs-second margin
fraction of valid live frames
stability across recent matcher runs
```

Начальные ориентиры допустимы, например:

```text
lock confidence >= 0.80
strong relocation >= 0.88
```

Но значения должны быть constants/config и подбираться тестами.

Initial global lock разреши после одного очень сильного unique result либо двух последовательных согласованных results.

## 22. Local matching

После lock:

```text
search center = LiveTransport predicted quarterBeat
```

Преобразуй predicted beat в nominal/reference time.

Начальный range:

```text
±30 s
```

Match каждые ~2 s на последних ~10 s live context.

Continuity prior:

```text
adjustedScore = acousticScore + continuityPenalty(distanceFromPrediction)
```

Не позволяй continuity полностью перебить очень сильный acoustic evidence: иначе app не сможет восстановиться после реального перескока.

## 23. Weak / reacquire policy

Если несколько local matches плохие или ambiguous:

```text
Locked -> Weak
```

Расширить search:

```text
±30 s -> ±60 s
```

Если всё ещё нет устойчивого кандидата:

```text
Weak -> Reacquiring
```

Запустить global candidate search, но до уверенного результата не прыгать по каждому новому top-1.

## 24. Этап 8 — интеграция с LiveTransport

Текущий `LiveTransport` уже держит:

```text
expectedBpmRt_
currentBpmRt_
positionRt_
```

Расширь его поддержкой absolute position correction, но не ломай sample-frame advance.

Добавь API по смыслу:

```cpp
void submitPositionObservation(const PositionObservation&) noexcept;
```

Observation приходит через atomic/seqlock mailbox, а применяется bounded logic внутри transport path.

### Small error

```text
abs(error) < 0.5 beat
```

Мягкая phase correction.

### Medium error

```text
0.5 .. 4 beats
```

Добавь temporary correction rate, например clamp:

```text
-0.4 .. +0.4 beat/s
```

Сделай так, чтобы correction постепенно исчезала по мере уменьшения error.

### Large error

```text
> 4 beats
```

Hard relocation только при strong confidence + uniqueness/stability.

Для первого global lock hard relocation разрешён сразу после валидного lock.

## 25. Очень важный invariant транспорта

Даже после добавления matcher:

```text
musical position advances from processed audio frames
```

Не переключай transport на:

- `System.nanoTime()`;
- `Handler`;
- Kotlin timer;
- Choreographer time.

UI polling не является clock source.

## 26. Tempo map integration

Сейчас `MainActivity.expectedBpm` фактически использует initial BPM.

Это нужно исправить.

Добавь Kotlin helpers в `MidiScore` или `ScoreNavigator`:

```kotlin
fun tempoAtQuarterBeat(position: Double): Double
fun quarterBeatToSeconds(position: Double): Double
fun secondsToQuarterBeat(seconds: Double): Double
```

В UI frame update не надо каждый frame делать JNI `setExpectedBpm` при микроскопической разнице.

Обновляй expected BPM когда transport пересёк tempo event либо value реально поменялось.

## 27. Beat tracker modernization

Position matcher не отменяет текущий BeatTracker.

Но текущий `BeatTracker.cpp` — bootstrap energy-rise detector.

После infrastructure можно перенести/заменить его на spectral-flux based tracker из analyzer features.

Не блокируй score-following milestone попыткой сразу сделать идеальный beat tracker.

Приоритет:

```text
1. absolute position acquisition works
2. local correction works
3. then improve beat stability
```

## 28. JNI changes

Текущий `getStateRaw()` возвращает 6 doubles. Расширь контракт согласованно.

Рекомендуемый semantic order:

```text
0 transportBpm
1 detectedBpm
2 quarterBeatPosition
3 beatConfidence
4 rms
5 running
6 positionConfidence
7 matchedQuarterBeatPosition
8 positionErrorBeats
9 positionStateCode
10 ambiguityMargin
11 validContextSeconds
```

Можно использовать data class/longer array как временный JNI contract.

Если начнёшь добавлять ещё много полей, лучше перейти к маленькому direct struct serialization или dedicated JNI accessors, но не создавай Java object из audio callback.

## 29. Kotlin TransportSnapshot

Расширь:

```kotlin
data class TransportSnapshot(
    val transportBpm: Double,
    val detectedBpm: Double,
    val quarterBeatPosition: Double,
    val beatConfidence: Double,
    val rms: Double,
    val running: Boolean,
    val positionConfidence: Double,
    val matchedQuarterBeatPosition: Double,
    val positionErrorBeats: Double,
    val positionState: PositionTrackingState,
    val ambiguityMargin: Double,
    val validContextSeconds: Double,
)
```

Имена можно корректировать, смысл сохранить.

## 30. MainActivity changes

При `loadMidi()` после parse:

1. stop old stream if running;
2. configure native transport;
3. передать score reference/native MIDI arrays;
4. reset position;
5. UI state -> ready.

При `startListening()`:

- scorer -> `Acquiring`;
- очистить старый live feature context;
- не считать текущий `0.0` надёжной physical position;
- UI показывает locating progress.

При `Stop`:

- stop input;
- можно оставить последнюю позицию на экране;
- tracking state -> Idle.

При `Reset`:

- reset transport to 0;
- если listening, желательно начать new acquisition либо явно перевести matcher в local around 0 в зависимости от UX; для первой версии безопаснее new acquisition.

## 31. UI минимум

Не трать время на redesign.

Добавь одну diagnostic/status строку, например:

```text
Position: locating 7.4/10 s
```

или:

```text
Position: LOCKED 91% | error +0.32 beat
```

При weak:

```text
Position: weak — reacquiring
```

Раздели:

```text
Beat confidence
Position confidence
```

Текущий единственный `confidenceText` можно временно заменить на комбинированную строку, но data model должна быть раздельной.

## 32. Не пытайся пока улучшать ScoreStaffView

`ScoreStaffView` остаётся placeholder engraving.

Для этой задачи его единственное важное свойство:

```text
quarterBeatPosition changes -> отображение следует transport
```

Не смешивай score-following PR с полноценной нотной гравировкой.

## 33. Debug diagnostics

Добавь compile-time/debug-only diagnostics без логирования из RT callback.

Полезные counters/state:

```text
sample rate
callback frames
ring fill
ring dropped samples/blocks
feature frames generated
valid live context seconds
matcher state
last match duration ms
number of coarse candidates
best normalized cost
second normalized cost
ambiguity margin
matched position
position error
beat confidence
position confidence
```

Producer обновляет atomics; UI/logging читает их с non-RT thread.

## 34. Performance budget

Matcher запускается примерно раз в 2 s, поэтому не обязан укладываться в один audio callback. Но он не должен постоянно занимать CPU и отставать от cadence.

Начальная цель на типичном Android phone:

```text
local match < 100 ms
full global acquisition preferably < 500 ms
```

Это цели профилирования, не acceptance blocker первого compile-ready commit.

Если global search медленный:

1. decimate reference;
2. улучшить coarse prefilter;
3. уменьшить K;
4. reuse buffers;
5. только потом думать о SIMD/NEON.

Не начинать с NEON optimization.

## 35. Безопасность от ложных прыжков

Это важнее, чем самый быстрый lock.

Нельзя:

```text
best candidate -> immediately reset transport
```

на каждом matcher pass.

Обязательны:

- confidence threshold;
- ambiguity margin;
- continuity prior после lock;
- stability/hysteresis;
- separate acquisition vs locked policies.

Лучше 3 секунды показать `ambiguous`, чем перескочить в другой припев.

## 36. Тестовый материал

Создай reproducible dev path.

Идеальный порядок тестирования:

1. synthetic `ScoreFeatureFrame` arrays;
2. MIDI-derived reference tested against artificially warped copy;
3. заранее записанный/рендеренный audio file через offline feature extractor, если добавишь test harness;
4. воспроизведение MIDI/аудио через колонку в микрофон телефона;
5. реальная живая игра.

Не tune thresholds только на одном live take.

## 37. Definition of done для первой полезной версии

Функция считается реализованной, когда выполняется сценарий:

```text
1. Launch app
2. Load MIDI
3. Start audio corresponding to a point in the middle of the song
4. Tap Listen
5. UI shows Locating
6. After roughly 6–12 s of informative material, state becomes LOCKED
7. Score jumps once to the correct neighborhood
8. Playhead then moves continuously
9. Local matcher periodically corrects drift without visible jitter
10. Stop does not crash/leak thread
11. Start again reacquires from current live music, not stale context
```

Дополнительно:

- repeated section -> ambiguous/continuity-safe behavior;
- short silence -> no random relocation;
- no allocation/locks/FFT/DTW in Oboe callback;
- Android 10 target preserved;
- existing MIDI parser/display features still work.

## 38. Что НЕ считать done

Не принимать реализацию, если она:

- только лучше оценивает BPM, но не умеет найти absolute position;
- сравнивает только onset rhythm и путает повторяющиеся части;
- делает raw microphone -> pitch note names;
- запускает DTW на UI thread;
- запускает DTW/FFT в Oboe callback;
- hard-jumps playhead каждые 2 секунды;
- ищет только около текущей позиции при первом `Listen` и поэтому не умеет стартовать с середины;
- сбрасывает matcher context не детерминированно;
- смешивает beat confidence и position confidence в один state.

## 39. После реализации

Обновить:

- `README.md` — новый пользовательский сценарий;
- `docs/architecture.md` — analyzer + matcher pipeline;
- `docs/realtime-rules.md` — явно запретить matcher в callback;
- developer diagnostics description;
- tuning constants/known limitations.

В PR summary отдельно написать:

1. как строится MIDI reference;
2. какие live features используются;
3. как работает global acquisition;
4. как работает local tracking;
5. какие thresholds/tuning constants выбраны;
6. какие тесты реально выполнены;
7. известные failure cases (повторы, слишком тихий сигнал, музыка без pitched content и т.д.).
