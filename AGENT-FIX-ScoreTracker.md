# Задача: исправить определение BPM по микрофону

Репозиторий: `einhander/scoretracker`.

Рабочая ветка: текущая `feature-score-following`.

## 1. Проблема

Сейчас определение BPM фактически не работает как настоящий tempo tracker.

Текущий путь:

```text
Oboe callback
    ↓
BeatTracker::process(raw audio)
    ↓
RMS блока
    ↓
рост RMS считается onset
    ↓
интервал между двумя соседними onset
    ↓
candidate BPM
    ↓
притягивание candidate к MIDI BPM
```

Основной проблемный файл:

```text
app/src/main/cpp/beat/BeatTracker.cpp
```

Текущий алгоритм делает ошибочное предположение:

```text
интервал между соседними атаками звука == период четвертной доли
```

Это неверно для реальной музыки.

Например, при реальном темпе 90 BPM восьмые ноты появляются примерно каждые:

```text
60 / 90 / 2 ≈ 0.333 s
```

Старый алгоритм получает:

```text
60 / 0.333 ≈ 180 BPM
```

Далее `normaliseCandidateBpm()` пытается привести результат к MIDI BPM, что создаёт нестабильный результат и проблемы half/double tempo.

Менять только коэффициенты:

```cpp
0.67
1.50
0.86
0.14
2.8
0.015
```

НЕ НУЖНО. Это не исправит архитектурную проблему.

---

# 2. Что уже есть в новой ветке

В `feature-score-following` уже реализован хороший фундамент DSP:

```text
Oboe
 ↓
SpscAudioRing
 ↓
AudioAnalyzer thread
 ↓
STFT
 ↓
SpectralFlux
 ↓
low / mid / high spectral flux
```

Файлы:

```text
app/src/main/cpp/audio/AudioAnalyzer.cpp
app/src/main/cpp/audio/AudioAnalyzer.h

app/src/main/cpp/dsp/Stft.cpp
app/src/main/cpp/dsp/Stft.h

app/src/main/cpp/dsp/SpectralFlux.cpp
app/src/main/cpp/dsp/SpectralFlux.h
```

Именно этот pipeline следует использовать для определения BPM.

Не надо делать второй FFT или второй анализ raw audio.

---

# 3. Требуемая архитектура

Новый путь BPM должен выглядеть так:

```text
Oboe callback
    │
    ├──→ LiveTransport::processFrames()
    │
    └──→ SpscAudioRing
              ↓
        AudioAnalyzer thread
              ↓
             STFT
              ↓
        SpectralFlux
              ↓
     onset envelope ~46.9 Hz
              ↓
       BeatTracker
       6–10 s history
              ↓
     autocorrelation /
      tempo candidates
              ↓
       detected BPM
              ↓
     LiveTransport target
              ↓
 time-based smooth adaptation
              ↓
       transport BPM
```

Критически важно:

**тяжёлое определение BPM больше не должно выполняться внутри `onAudioReady()`.**

`onAudioReady()` — hard realtime callback.

В нём нельзя делать:

- FFT;
- динамические аллокации;
- mutex;
- логирование;
- сложный поиск BPM;
- обработку многосекундных буферов.

---

# 4. BeatTracker оставить, но полностью изменить его назначение

Можно сохранить:

```text
beat/BeatTracker.h
beat/BeatTracker.cpp
```

чтобы не делать ненужный большой refactoring.

Но `BeatTracker` должен перестать принимать raw PCM.

Сейчас:

```cpp
BeatObservation process(
    const float* data,
    int32_t numFrames,
    int32_t channelCount
);
```

Нужно перейти примерно к:

```cpp
BeatObservation processFlux(
    const std::array<float, 3>& bands,
    float energy,
    int64_t centerAudioFrame
) noexcept;
```

Дополнительно при конфигурации BeatTracker должен знать:

```cpp
expectedBpm
sampleRate
STFT hopSize
```

Например:

```cpp
void configure(
    double expectedBpm,
    int32_t sampleRate,
    int32_t hopSize
) noexcept;
```

Feature rate определяется:

```cpp
featureRate = sampleRate / hopSize;
```

При:

```text
sampleRate = 48000
hop = 1024
```

получается:

```text
46.875 onset-envelope samples/s
```

Этого достаточно для определения BPM.

---

# 5. Важное изменение AudioAnalyzer

Сейчас имеется:

```cpp
const auto bands = flux_->process(stft_->magnitude());

if (center < nextGridCenter_)
    continue;
```

После этого feature pipeline уменьшается примерно до 10 Hz для score matching.

**BPM нельзя считать только по этим 10 Hz frames.**

BeatTracker должен получать spectral flux на каждом STFT hop, то есть ДО:

```cpp
if (center < nextGridCenter_)
    continue;
```

Пример структуры:

```cpp
if (stft_->process(buffer, count)) {
    const int64_t center = ...;

    const auto bands =
        flux_->process(stft_->magnitude());

    // FAST TEMPO ANALYSIS
    const auto tempoObservation =
        beatTracker_.processFlux(
            bands,
            stft_->frameEnergy(),
            center
        );

    if (tempoObservation.updated) {
        transport_->submitTempoObservation(
            tempoObservation
        );
    }

    // Existing slow score-following path
    if (center < nextGridCenter_)
        continue;

    AudioFeatureFrame frame;
    ...
}
```

Existing score-following logic нельзя ломать.

---

# 6. Формирование onset envelope

Нельзя просто делать:

```cpp
bands[0] + bands[1] + bands[2]
```

и считать это готовой величиной tempo onset.

Разные spectral bands имеют разные абсолютные масштабы.

Использовать адаптивную нормализацию каждой полосы.

Например BeatTracker может поддерживать для каждой полосы:

```cpp
mean_[3]
deviation_[3]
```

Обновление:

```cpp
mean =
    0.99 * mean +
    0.01 * x;

deviation =
    0.99 * deviation +
    0.01 * abs(x - mean);
```

Нормализованная положительная активность:

```cpp
normalized =
    max(
        0,
        (x - mean) /
        (deviation + epsilon)
    );
```

Затем собрать onset envelope примерно как:

```cpp
onset =
    0.8 * low +
    1.0 * mid +
    0.7 * high;
```

Не считать эти коэффициенты догмой. Они должны быть отдельными понятными constants.

Ограничить экстремальные выбросы:

```cpp
normalized = clamp(normalized, 0.0f, 8.0f);
```

Не менять существующий `frame.onset`, используемый PositionMatcher, если для этого нет отдельной причины.

Tempo estimator должен использовать собственную нормализованную версию flux.

---

# 7. Нельзя больше считать BPM по двум соседним onset

Полностью удалить логику вида:

```cpp
delta = onset - previousOnset;

candidate =
    60 * sampleRate / delta;
```

как основной estimator.

Один IOI может соответствовать:

- четверти;
- восьмой;
- шестнадцатой;
- синкопе;
- гитарному удару;
- хай-хэту;
- басовой ноте.

Tempo нужно определять по нескольким секундам контекста.

---

# 8. История onset envelope

Хранить кольцевой буфер приблизительно:

```text
8 секунд
```

Допустимо:

```text
6–10 секунд
```

При 46.875 Hz восемь секунд — примерно:

```text
375 значений
```

Можно выделить фиксированный массив с запасом, например:

```cpp
std::array<float, 512>
```

или 1024.

Не делать allocation на каждом `processFlux()`.

---

# 9. Алгоритм оценки BPM

Для первой нормальной реализации использовать normalized autocorrelation onset envelope.

Диапазон:

```text
40–240 BPM
```

При необходимости внешняя система всё равно может ограничивать итоговый BPM диапазоном 30–300.

Для каждого BPM определить lag:

```text
lag =
    featureRate * 60 / BPM
```

Рассчитать normalized autocorrelation:

```text
             Σ x[n] x[n-lag]
AC(lag) = ------------------------
           sqrt(Σx² · Σxlag²) + ε
```

Использовать последние примерно 6–8 секунд onset envelope.

Оценку выполнять не на каждом frame, а примерно:

```text
4 раза в секунду
```

то есть каждые:

```text
~250 ms
```

---

# 10. Half/double tempo

Это отдельная обязательная задача.

Например один и тот же onset pattern может давать кандидатов:

```text
60
120
240 BPM
```

или:

```text
45
90
180 BPM
```

Нельзя исправлять это правилом:

```cpp
while (bpm < expected * ...)
    bpm *= 2;

while (bpm > expected * ...)
    bpm *= 0.5;
```

Вместо этого найти несколько локальных максимумов autocorrelation и оценивать кандидаты.

Использовать MIDI BPM как **prior**, но не как готовый результат.

Пример:

```text
finalScore =
    correlationScore +
    weakMidiPrior
```

Для prior удобно использовать расстояние в октавах:

```cpp
distance =
    abs(log2(candidateBpm / expectedBpm));
```

и smooth bonus:

```cpp
prior =
    exp(
        -0.5 *
        pow(distance / 0.5, 2)
    );
```

Вес prior должен быть небольшой, например порядка:

```text
10–20 % итогового score
```

То есть MIDI BPM:

```text
помогает выбрать метрический уровень,
но не заменяет измерение.
```

Это принципиально.

---

# 11. Не инициализировать detected BPM значением MIDI BPM

Сейчас:

```cpp
estimatedBpm_ = expectedBpm;
```

и затем это значение может публиковаться как будто оно измерено микрофоном.

Это неправильно.

Необходимо разделить:

```text
expected BPM
detected BPM
transport BPM
```

Семантика:

```text
expectedBpm
    BPM из MIDI

detectedBpm
    BPM, реально найденный по аудио

transportBpm
    BPM, которым в данный момент движется курсор
```

После старта:

```text
expectedBpm = например 120
transportBpm = 120
detectedBpm = 0
```

Пока tempo estimator не получил достаточно данных UI уже умеет показывать:

```text
--.- BPM
```

потому что в `MainActivity.kt` есть проверка:

```kotlin
state.detectedBpm > 1.0
```

Поэтому отдельного изменения UI для этого не требуется.

---

# 12. Confidence

Tempo estimator должен иметь настоящую confidence.

Учитывать как минимум:

- величину лучшего autocorrelation peak;
- разницу между лучшим и вторым кандидатом;
- количество накопленного контекста;
- наличие достаточной spectral activity.

Не публиковать valid tempo после двух ударов.

Начальный lock должен обычно появляться примерно через:

```text
3–5 секунд
```

Полная стабилизация допустима приблизительно:

```text
5–8 секунд
```

Это соответствует назначению приложения.

Пример поведения:

```text
0–3 s:
detectedBpm = 0
confidence = low

3–5 s:
candidate появляется,
confidence растёт

5+ s:
стабильный detected BPM
```

При тишине confidence должна снижаться.

После нескольких секунд отсутствия полезной rhythmic activity:

```text
tempoValid = false
detectedBpm = 0
```

Не выдавать MIDI BPM как измеренное значение.

---

# 13. Сглаживание detected BPM

Не прыгать напрямую между:

```text
89 → 181 → 91 → 178
```

После выбора метрического кандидата сглаживать BPM.

Лучше сглаживать в log-domain:

```cpp
logBpm =
    (1.0 - alpha) * log(previousBpm) +
    alpha * log(candidateBpm);

bpm = exp(logBpm);
```

Начальный `alpha` можно использовать около:

```text
0.2–0.3
```

Но half/double resolution нужно делать ДО smoothing.

---

# 14. Передача BPM из AudioAnalyzer в LiveTransport

Tempo estimator работает в analyzer thread.

`LiveTransport::processFrames()` работает из audio callback.

Не передавать данные через mutex.

Добавить в `LiveTransport` atomic latest target.

Например:

```cpp
std::atomic<double> tempoTargetBpm_{0.0};
std::atomic<float> tempoConfidence_{0.0f};
std::atomic<bool> tempoTargetValid_{false};
```

Добавить:

```cpp
void LiveTransport::submitTempoObservation(
    const BeatObservation& observation
) noexcept;
```

Эта функция вызывается analyzer thread.

Она должна обновлять:

```text
latest detected BPM
confidence
valid flag
```

через atomics.

---

# 15. Убрать BeatTracker из Oboe callback

Сейчас:

```cpp
const BeatObservation observation =
    beatTracker_.process(
        input,
        numFrames,
        channelCount_
    );

transport_.processFrames(
    numFrames,
    sampleRate_,
    observation
);
```

Этого больше быть не должно.

Должно быть примерно:

```cpp
transport_.processFrames(
    numFrames,
    sampleRate_
);

ring_.write(
    input,
    static_cast<size_t>(numFrames)
);
```

`onAudioReady()` оставить максимально простым.

---

# 16. BeatTracker лучше переместить логически в AudioAnalyzer

Сейчас `BeatTracker` принадлежит:

```text
OboeInputEngine
```

После изменения логичнее, чтобы он принадлежал:

```text
AudioAnalyzer
```

То есть примерно:

```cpp
class AudioAnalyzer {
    ...
    BeatTracker beatTracker_;
};
```

`AudioAnalyzer::start()` должен получить текущий expected BPM:

```cpp
bool start(
    int32_t sampleRate,
    double expectedBpm
);
```

После создания `Stft`:

```cpp
beatTracker_.configure(
    expectedBpm,
    sampleRate,
    static_cast<int32_t>(stft_->hop())
);
```

---

# 17. Изменение MIDI tempo region во время исполнения

Сейчас:

```cpp
OboeInputEngine::setExpectedBpm()
```

вызывает:

```cpp
beatTracker_.setExpectedBpm()
```

После переноса BeatTracker в AudioAnalyzer сделать:

```cpp
analyzer_.setExpectedBpm(expectedBpm_);
```

`setExpectedBpm()`:

- не должен очищать накопленный audio history;
- не должен сбрасывать confidence в ноль;
- меняет только MIDI prior;
- следующие tempo evaluations уже используют новый prior.

Это важно при переходе через tempo changes в MIDI.

---

# 18. Исправить LiveTransport

Сейчас BPM transport меняется только при:

```cpp
if (observation.tempoValid)
```

и затем:

```cpp
currentBpmRt_ =
    0.985 * currentBpmRt_ +
    0.015 * target;
```

Это зависит от частоты возникновения onset и поэтому имеет неправильную временную характеристику.

Нужно сделать сглаживание **time-based на каждом audio callback**.

Пример:

```cpp
const double dt =
    static_cast<double>(numFrames) /
    sampleRate;

const double tau = 0.7;

const double alpha =
    1.0 - std::exp(-dt / tau);

currentBpmRt_ +=
    alpha *
    (targetBpm - currentBpmRt_);
```

При средней confidence можно использовать более медленную реакцию:

```text
tau ≈ 1.0–1.5 s
```

При высокой confidence:

```text
tau ≈ 0.5–0.8 s
```

Таким образом переход:

```text
120 BPM → 90 BPM
```

не должен занимать 15–20 секунд.

Ориентир:

```text
через ~2–3 секунды после уверенного определения 90 BPM
transport должен быть уже около 90–95 BPM.
```

---

# 19. Поведение при потере tempo lock

Если `tempoTargetValid == false`, transport не должен мгновенно прыгать.

Можно плавно возвращать его к MIDI expected BPM с большой постоянной времени:

```text
tau ≈ 3–5 s
```

То есть:

```text
есть хороший detected BPM:
    follow detected BPM

нет rhythm evidence:
    постепенно возвращаться к MIDI BPM
```

---

# 20. Phase correction

Старый BeatTracker одновременно пытался определять:

```text
BPM
+
beat phase
```

через `predictedNextBeatFrame_`.

Эту старую phase logic НЕ переносить автоматически.

На первом этапе исправления BPM:

```cpp
phaseValid = false;
phaseCorrectionBeats = 0.0;
```

Это лучше, чем передавать неправильную фазу.

В новой ветке PositionMatcher уже отдельно исправляет положение курсора.

Beat-phase PLL можно реализовать отдельным следующим этапом после того, как BPM estimator будет доказано работать.

---

# 21. RMS / energy

После удаления старого:

```cpp
BeatTracker::process(raw audio)
```

не потерять показатель audio level.

Можно публиковать:

```cpp
stft_->frameEnergy()
```

через tempo observation.

Либо оставить очень дешёвый RMS meter непосредственно в callback.

Но нельзя сохранять старый BeatTracker только ради RMS.

---

# 22. Обязательные тесты

Текущих `test_beat_tracker.cpp` недостаточно.

Добавить deterministic host tests.

## Test 1 — простой 120 BPM

Synthetic onset envelope:

```text
четвертные импульсы
120 BPM
```

Ожидается:

```text
detected BPM ≈ 120
```

Погрешность:

```text
±2–3 BPM
```

---

## Test 2 — 90 BPM при MIDI prior 120

```text
expected = 120
audio tempo = 90
```

Ожидается:

```text
detected ≈ 90
```

а не 120.

---

## Test 3 — восьмые ноты

Ключевой regression test.

```text
real tempo = 90 BPM
onsets = eighth notes
expected MIDI BPM = 120
```

Алгоритм не должен стабильно возвращать:

```text
180 BPM
```

Ожидаемый метрический уровень:

```text
~90 BPM
```

с учётом MIDI prior и tempo-family scoring.

---

## Test 4 — шестнадцатые

Например:

```text
tempo = 120 BPM
регулярные subdivision
```

Не должен определяться:

```text
240 BPM
```

только потому, что атаки идут чаще beat period.

---

## Test 5 — tempo change

Сгенерировать:

```text
первые 8 s: 120 BPM
следующие 10 s: 90 BPM
```

Проверить:

```text
initial lock ≈ 120

после перехода:
detected BPM сходится к ≈90
```

---

## Test 6 — silence

Передать несколько секунд:

```text
zero onset
zero/low energy
```

Нельзя получать:

```text
detectedBpm = expectedBpm
```

Должно быть:

```text
tempoValid = false
detectedBpm = 0
```

---

## Test 7 — amplitude variation

Один и тот же 120 BPM pattern:

```text
quiet
medium
loud
```

должен давать примерно одинаковый BPM.

Это проверяет adaptive normalization.

---

## Test 8 — transport response

Начальное:

```text
transport = 120
```

Подать valid tempo target:

```text
90 BPM
```

Запускать:

```cpp
processFrames(480, 48000)
```

как 10 ms callbacks.

Через примерно:

```text
2–3 s
```

transport BPM должен оказаться примерно:

```text
90–95 BPM
```

а не оставаться около:

```text
110–115 BPM.
```

---

# 23. Existing tests не ломать

Обязательно сохранить работоспособность:

```text
SPSC ring
FFT
Hann
Chroma
SpectralFlux
Feature pipeline
PositionMatcher
DTW
LiveTransport position correction
MIDI parser
UI/JVM tests
```

Исправление BPM не должно изменять score-following алгоритм без необходимости.

---

# 24. Проверка сборки

Запустить host C++ tests:

```bash
cmake \
  -S app/src/main/cpp \
  -B /tmp/scoretracker-host

cmake \
  --build /tmp/scoretracker-host \
  -j

ctest \
  --test-dir /tmp/scoretracker-host \
  --output-on-failure
```

Затем:

```bash
./build.sh test
```

И Android debug build:

```bash
./build.sh debug
```

Не менять:

```text
targetSdk = 29
```

Не добавлять новые heavyweight DSP libraries.

Использовать существующий C++ DSP.

---

# 25. Ручной тест на Android

После сборки проверить минимум три сценария.

### Сценарий A

MIDI:

```text
120 BPM
```

Играть примерно:

```text
120 BPM
```

Ожидается:

```text
detected BPM стабилизируется около 120
transport BPM около 120
```

### Сценарий B

MIDI:

```text
120 BPM
```

Играть заметно медленнее:

```text
~90 BPM
```

Через несколько секунд ожидается:

```text
detected ≈ 90
transport начинает плавно приближаться к 90
```

### Сценарий C

Играть 90 BPM, но с большим количеством:

```text
восьмых
гитарных ударов
hi-hat
басовых атак
```

BPM не должен прыгать постоянно между:

```text
90
120
180
```

---

# 26. Диагностика

Для отладки разрешается добавить diagnostic state, но НЕ логировать из Oboe callback.

Полезно видеть:

```text
raw candidate BPM
selected candidate BPM
best autocorrelation score
second score
confidence
expected MIDI BPM
detected BPM
transport BPM
```

Если нужны Android logs — писать их из `AudioAnalyzer` worker thread, не из `onAudioReady()`.

После отладки убрать spam.

---

# 27. Что НЕ делать

Не считать исправлением следующие изменения:

```text
увеличить RMS threshold
уменьшить RMS threshold
поменять refractory period
изменить 0.67 на 0.5
изменить 1.50 на 2.0
изменить smoothing 0.14
изменить smoothing 0.015
```

Они могут изменить симптом, но фундаментальную проблему не устранят.

Также не нужно:

```text
pitch detection
audio-to-MIDI
распознавание нот
нейросеть
Essentia
aubio целиком
новый audio framework
```

Для этой задачи достаточно существующих:

```text
STFT
SpectralFlux
autocorrelation
MIDI BPM prior
```

---

# 28. Definition of Done

Исправление считать законченным только когда выполняются ВСЕ условия:

1. `BeatTracker` больше не определяет BPM по интервалу двух соседних RMS onset.

2. BPM вычисляется по многосекундному spectral-flux onset envelope.

3. Расчёт tempo выполняется в `AudioAnalyzer` thread, не в Oboe callback.

4. `detectedBpm` до настоящего lock равен `0`, а не MIDI BPM.

5. MIDI BPM используется как prior, а не как измеренное значение.

6. Есть обработка half/double tempo.

7. Восьмые/шестнадцатые не должны автоматически удваивать BPM.

8. `LiveTransport` использует time-based smoothing.

9. Переход transport с 120 к измеренным 90 BPM занимает несколько секунд, а не десятки секунд.

10. При тишине `detectedBpm` становится invalid/0.

11. Existing score-following pipeline продолжает работать.

12. Добавлены deterministic host regression tests.

13. Проходят:

```text
host C++ tests
./build.sh test
./build.sh debug
```

---

# Итоговая цель

После исправления архитектура должна иметь чёткое разделение:

```text
MIDI
 └── expected BPM
        │
        │ weak prior
        ▼
Microphone → STFT → spectral flux → tempo estimator
                                  │
                                  └── detected BPM
                                           │
                                           ▼
                                   LiveTransport
                                           │
                                    smooth adaptation
                                           │
                                           ▼
                                     transport BPM
```

Главный принцип:

**MIDI говорит, какой темп ожидается. Микрофон измеряет, какой темп реально исполняется. Transport плавно следует за измеренным темпом.**