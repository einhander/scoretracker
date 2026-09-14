# TempoScore — спецификация

## 1. Назначение

TempoScore — Android-приложение-помощник для репетиций и игры на музыкальных инструментах. Пользователь загружает MIDI-файл произведения. Приложение показывает текущую позицию в партитуре и ближайший музыкальный материал, одновременно слушая живое исполнение через микрофон телефона и подстраивая скорость виртуального транспорта под фактический темп ансамбля.

Целевая платформа: **Android 10 / API 29**.

Ключевая идея: приложение **не распознаёт сыгранные ноты** и не делает audio-to-MIDI. Оно извлекает из микрофона только ритмическую информацию: атаки, периодичность, beat phase, текущий BPM и confidence. Ноты берутся исключительно из загруженного MIDI.

## 2. Жёсткие продуктовые ограничения

1. Живой вход — **только микрофон устройства**.
2. Не подключать и не требовать MIDI-клавиатуру/контроллер.
3. Не реализовывать pitch detection, chord recognition, transcription или audio-to-MIDI в базовом продукте.
4. Базовый/ожидаемый BPM берётся из MIDI tempo map.
5. Приложение должно работать локально, без сервера и облака.
6. Основная целевая версия Android — Android 10, `targetSdk = 29`.
7. В проекте сразу присутствуют C++/NDK/CMake/Oboe, чтобы production DSP не пришлось переносить из Kotlin позже.

## 3. Пользовательский сценарий MVP

1. Пользователь открывает приложение.
2. Нажимает `Load MIDI`.
3. Выбирает `.mid`/`.midi` через Storage Access Framework.
4. Приложение читает:
   - PPQ;
   - Note On / Note Off;
   - tempo map;
   - time signatures;
   - длину произведения.
5. На экране появляются:
   - нотный/партитурный preview;
   - текущий такт и доля;
   - текущие ноты;
   - ближайшие следующие ноты;
   - BPM из MIDI.
6. Пользователь начинает с начала или выбирает известную стартовую позицию.
7. Нажимает `Start listening`.
8. Приложение получает микрофонный PCM через Oboe.
9. Beat tracker оценивает текущий темп и beat phase относительно MIDI prior.
10. Live transport плавно ускоряется/замедляется вслед за исполнением.
11. Фиксированный playhead показывает «где мы сейчас», а материал движется относительно него.

## 4. Что означает «где мы сейчас»

Внутренний транспорт хранит непрерывную координату:

```text
quarterBeatPosition = число четвертных долей от выбранной стартовой точки
```

Пример:

```text
0.00  = такт 1, доля 1 в 4/4
1.00  = такт 1, доля 2
3.50  = между долями 4 и следующим тактом
4.00  = такт 2, доля 1
```

Time signature используется для преобразования этой координаты в `bar + beat`.

Позиция развивается по формуле:

```text
dPosition/dt = transportBpm / 60
```

но регулярно корректируется beat tracker'ом по аудио.

## 5. Принципиальное ограничение абсолютной позиции

Только по BPM/ритму невозможно однозначно определить, какой именно сейчас такт произведения. Если такты 12 и 72 имеют одинаковую пульсацию, beat tracker не может различить их без анализа музыкального содержания.

Поэтому базовый продукт гарантирует корректную позицию при условии, что:

- стартовая позиция известна;
- далее исполнение движется вперёд без произвольного перескока на другой раздел.

Для начала достаточно режимов:

```text
Start from beginning
Start from selected bar
```

Если ансамбль вручную перескочил на другой куплет, пользователь должен иметь возможность быстро переставить playhead. Автоматический section recognition не входит в MVP.

## 6. Поддерживаемый MIDI

### MVP

- Standard MIDI File format 0;
- Standard MIDI File format 1;
- PPQ timing;
- running status;
- Note On / Note Off;
- tempo meta event `0x51`;
- time-signature meta event `0x58`;
- пропуск SysEx и неизвестных meta events.

### Не требуется в MVP

- SMPTE time division;
- полноценная интерпретация expression/CC;
- SysEx;
- MIDI playback/synthesis;
- MIDI device input.

## 7. Tempo map

MIDI может содержать несколько tempo events. Трекер должен использовать BPM MIDI не как жёсткое значение, а как prior.

В простой композиции:

```text
MIDI prior = 120 BPM
live performance = 116 BPM
transport ~= 116 BPM
```

При MIDI tempo change:

```text
MIDI 120 -> 140 BPM
```

production transport должен переносить накопленный коэффициент живого исполнения на новую область tempo map, например:

```text
live tempo scale = 0.96
expected after MIDI change = 140 * 0.96 = 134.4 BPM
```

Это предпочтительнее, чем мгновенно сбрасывать live tempo к 140.

## 8. Аудио-вход

### Требования

- `RECORD_AUDIO` runtime permission;
- C++ Oboe input stream;
- low-latency mode;
- mono предпочтительно;
- actual sample rate получать после открытия stream, не считать жёстко 48 kHz;
- float PCM внутри DSP;
- сначала пробовать `InputPreset::Unprocessed`;
- при отказе fallback на `VoiceRecognition`;
- никаких требований к Bluetooth или внешнему аудиоинтерфейсу.

### Входной материал

Трекер должен быть рассчитан на смесь:

- барабанов;
- бас-гитары;
- электрической/акустической гитары;
- клавиш;
- нескольких инструментов одновременно.

Алгоритм не должен искать конкретный kick/snare и не должен зависеть от нотной высоты.

## 9. Beat tracking — production target

Текущий каркас содержит только bootstrap-алгоритм по росту энергии. Он нужен для проверки потока данных и JNI, но **не является целевым алгоритмом**.

Целевая цепочка:

```text
microphone PCM
   -> optional DC removal / light conditioning
   -> STFT
   -> multiband positive spectral flux
   -> adaptive onset envelope
   -> tempo hypotheses around MIDI prior
   -> beat period continuity model
   -> phase/PLL tracker
   -> confidence
   -> LiveTransport
```

### 9.1 STFT

Стартовые параметры для эксперимента:

- sample rate: фактический stream rate;
- FFT: 1024 или 2048 samples;
- hop: 256 или 512 samples;
- Hann window;
- анализ до приблизительно 8–10 kHz достаточен для beat tracking.

Параметры должны быть измерены на реальном телефоне, а не считаться окончательными заранее.

### 9.2 Multiband onset envelope

Полезно отдельно учитывать диапазоны, чтобы приложение не зависело только от тарелок или только от баса, например:

```text
low:   ~40–220 Hz
mid:   ~220–2000 Hz
high:  ~2–10 kHz
```

Для каждого диапазона считать положительный spectral flux, затем нормализовать и смешивать.

### 9.3 Adaptive threshold

Нельзя использовать один фиксированный порог амплитуды. Нужны:

- локальный median/EMA noise floor;
- adaptive threshold;
- refractory period;
- ограничение ложных повторных onsets.

### 9.4 Tempo prior

Пусть `B_midi` — ожидаемый BPM в текущей области MIDI.

Поиск должен быть ограничен разумным диапазоном, например сначала:

```text
0.55 * B_midi <= B_live <= 1.80 * B_midi
```

Практический UI может дать более узкий режим, например ±25%.

Prior должен решать проблему half/double tempo:

```text
60 / 120 / 240 BPM
```

выбирается гипотеза, наиболее согласованная с MIDI prior и предыдущим transport BPM.

### 9.5 Tempo continuity

Нельзя каждые 1–2 секунды независимо заменять BPM новым результатом.

Нужна динамическая модель:

```text
previous tempo
 + small acceleration/deceleration
 + audio evidence
```

Допустимы PLL, Kalman filter, particle filter или собственный bounded state estimator. Для MVP предпочтителен простой и объяснимый PLL/alpha-beta подход.

### 9.6 Phase tracking

Нужно хранить не только BPM, но и фазу beat grid.

Beat observation корректирует predicted beat time, но correction должна быть ограничена. Одиночный гитарный восьмой удар не должен внезапно сдвинуть транспорт на половину доли.

### 9.7 Confidence

Трекер должен отдавать `0..1` confidence.

Confidence влияет на transport:

- высокая: разрешены tempo + небольшие phase corrections;
- средняя: корректировать только tempo, осторожно;
- низкая: продолжать по последнему стабильному tempo без резких перестроек;
- очень низкая длительно: показать UI состояние `tracking weak/lost`.

## 10. Тишина, паузы и остановки

Нельзя немедленно останавливать транспорт при отсутствии onsets — в музыке бывают паузы.

Базовая политика:

1. короткий пробел в ритмической информации — продолжать по последнему transport BPM;
2. confidence постепенно падает;
3. UI показывает, что tracking слабый;
4. режим «останавливать транспорт при реальной остановке ансамбля» добавить отдельно и сделать настраиваемым;
5. до production-решения не путать музыкальную паузу с остановкой репетиции.

## 11. Разделение detected BPM и transport BPM

Обязательно иметь два значения.

`detectedBpm`:

- может быстро меняться;
- диагностическое;
- отражает оценку DSP.

`transportBpm`:

- плавное;
- используется для позиции и визуализации;
- меняется с ограниченной скоростью;
- защищено от единичных ошибочных оценок.

Пример:

```text
detected:  117, 114, 120, 116, 118
transport: 119.0, 118.4, 118.1, 117.8, 117.8
```

## 12. Native threading model

Production DSP не должен выполняться полностью в Oboe callback.

Целевая схема:

```text
Oboe RT callback
   -> preallocated SPSC ring buffer
   -> native analysis thread
        STFT / spectral flux / beat tracking
   -> atomic latest BeatObservation
   -> RT callback reads observation
   -> sample-frame LiveTransport
   -> atomic TransportState
   -> JNI read from Kotlin UI
```

### RT callback запрещено

- `new/delete/malloc/free`;
- mutex/condition_variable;
- blocking queue;
- logging;
- file I/O;
- network I/O;
- JNI/Kotlin calls;
- создание потоков;
- тяжёлые FFT после перехода на production tracker.

## 13. Временная база

Музыкальный transport привязан к количеству обработанных audio frames.

Запрещено использовать для музыкального времени:

- `System.currentTimeMillis()`;
- `System.nanoTime()`;
- `Handler.postDelayed()`;
- Kotlin coroutines delay;
- Java Timer.

Они допустимы только для UI/служебных задач.

## 14. UI

### Основной экран

Минимальный интерфейс:

```text
[Load MIDI] [Start/Stop] [Reset]

MIDI 120.0 | LIVE 117.4 | transport 118.1 BPM
Bar 24 Beat 3.42 | Confidence 82%

                 NOW
                  |
... notes ....... | .... upcoming notes ....
                  |

Now:  C4 E4
Next: G4 B4
```

### Поведение партитуры

Предпочтительный UX:

- playhead стоит примерно на 30–40% ширины экрана;
- прошлое уходит влево;
- будущий материал виден справа минимум на 1–2 такта;
- текущие и ближайшие ноты выделяются;
- экран не должен прыгать при мелких BPM fluctuations.

### Ориентация

MVP ориентирован на landscape, так как основная задача — видеть горизонтальное будущее партитуры.

## 15. Нотное отображение

Текущий `ScoreStaffView` в каркасе — технический placeholder.

Для полноценного нотного стана потребуется отдельный слой engraving:

- MIDI quantization;
- определение длительностей;
- группировка в такты;
- clef selection;
- accidentals и key signature;
- rests;
- chords;
- beams;
- ties;
- голоса;
- grand staff для piano при необходимости.

MIDI сам по себе неоднозначен относительно нотной записи, поэтому production notation следует рассматривать как отдельную подсистему.

На первом рабочем этапе допустимы два режима:

1. piano-roll / timing lane — самый надёжный;
2. простой staff preview — для UX.

Правильную гравировку делать после устойчивого live transport.

## 16. Архитектура модулей

```text
Kotlin
  MainActivity
  midi/
    MidiFileParser
    MidiModels
  score/
    ScoreNavigator
  ui/
    ScoreStaffView
  NativeAudioBridge

C++
  audio/
    OboeInputEngine
  beat/
    BeatTracker
    [future] SpectralFlux
    [future] TempoEstimator
    [future] BeatPll
  transport/
    LiveTransport
  native_audio_jni.cpp
```

## 17. Состояния runtime

Рекомендуемые состояния tracking:

```text
IDLE
ARMED
ACQUIRING
TRACKING
WEAK
LOST
```

MVP может начать с bool `running` + confidence, но production UI должен отличать acquisition от уверенного tracking.

## 18. Настройки, которые стоит добавить после MVP

- диапазон допустимого отклонения от MIDI BPM;
- tracking sensitivity;
- phase correction strength;
- start from bar;
- count-in 1–2 такта;
- отображаемый горизонт будущего;
- режим piano-roll / staff;
- диагностическая панель audio API / sample rate / callback size / onset / confidence.

## 19. Ошибки и recovery

Приложение должно корректно обрабатывать:

- отказ в microphone permission;
- неподдерживаемый/битый MIDI;
- SMPTE MIDI с понятным сообщением;
- Oboe open failure;
- disconnect/reconfigure audio device;
- app pause/resume;
- очень слабый сигнал;
- отсутствие уверенного beat.

Native stream disconnect не должен приводить к crash. Production версия должна безопасно переоткрывать stream из non-RT management thread.

## 20. Производительность

Цели для Android 10-class hardware:

- UI 30–60 FPS;
- RT callback без allocation/lock;
- устойчивый микрофонный stream без underrun/overrun со стороны приложения;
- DSP budget желательно < 10–15% одного современного mobile CPU core в среднем;
- задержка отображаемой tempo/phase реакции: ориентир < 150–250 ms при стабильном ритме;
- никаких сетевых зависимостей во время исполнения.

Это инженерные цели, а не гарантированные цифры до измерений на реальных устройствах.

## 21. Privacy

- аудио не записывается на диск по умолчанию;
- аудио не отправляется в сеть;
- анализ выполняется локально;
- диагностическая запись аудио возможна только как явно включаемый dev/debug режим.

## 22. Acceptance criteria для первого рабочего релиза

### MIDI/UI

- приложение открывает корректный SMF 0/1;
- показывает BPM, такт/долю, текущие и следующие ноты;
- playhead двигается по загруженному MIDI;
- старт можно сбросить к началу;
- отсутствует зависимость от MIDI-клавиатуры.

### Audio

- Oboe microphone stream стартует на Android 10;
- actual sample rate отображается в debug информации;
- при равномерном metronome/drum loop около MIDI BPM tracker стабильно захватывает темп;
- half/double BPM не должен постоянно перескакивать благодаря MIDI prior.

### Transport

- при плавном изменении темпа ±15% курсор плавно следует изменению;
- одиночный ложный onset не вызывает заметного скачка позиции;
- краткая пауза в onset data не сбрасывает позицию;
- detected BPM и transport BPM существуют отдельно.

### Real-time

- production callback не содержит allocation, locks, file I/O, logs или JNI calls;
- тяжёлый DSP вынесен в native worker.

## 23. Порядок разработки

1. Стабильная сборка и запуск каркаса.
2. MIDI parser + тесты.
3. Oboe microphone diagnostics.
4. Надёжный sample-frame transport без DSP.
5. Offline тестовый harness для onset/tempo алгоритмов.
6. Multiband spectral-flux onset detector.
7. MIDI-prior tempo estimator.
8. PLL/phase tracker + confidence.
9. Интеграция с live transport.
10. Отладка на барабанах, bass+guitar, полном ансамбле.
11. UI polishing.
12. Только затем полноценный notation engraving.

## 24. Не делать без отдельного решения

- не добавлять FluidSynth;
- не добавлять MIDI controller support;
- не добавлять note/pitch recognition;
- не делать cloud backend;
- не привязывать transport к Android timers;
- не заменять Oboe на Java `AudioRecord` как основной production backend;
- не поднимать `targetSdk` выше 29 только ради «обновления» без явного требования;
- не считать bootstrap `BeatTracker` готовым production DSP.
