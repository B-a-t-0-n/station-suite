# OPENSTATION и наша модификация Макс

Изучено 16 ноября 2026 года: [Olegu621/OPENSTATION](https://github.com/Olegu621/OPENSTATION),
commit [`be24e697896fedf556a246c6ee951e7d55c21393`](https://github.com/Olegu621/OPENSTATION/tree/be24e697896fedf556a246c6ee951e7d55c21393),
версия daemon/release `1.6.7`. Только статическое исследование исходников;
установщик, сборка и бинарники проекта не запускались. Код и ассеты проекта
не включены в Station Suite.

## Практический вывод

Наибольшая ценность для нас — алгоритмы кольца и конкретный источник цифрового
музыкального спектра. Полная установка — отдельная архитектурная миграция:
она требует root ADB, меняет системный запуск и отключает часть служб Яндекса.
Наш вариант сохраняет native Quasar прошивки `20221212`, его слово активации,
музыку и системную индикацию. [Проверка root в deploy](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/scripts/deploy.ps1#L427-L431),
[отключение служб](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/scripts/deploy.ps1#L1138-L1214).

## Что полезно

| Возможность | Что есть в коде | Применение у нас |
| --- | --- | --- |
| RGB-кольцо | Цвет, дыхание, радуга, вращение, музыка и голос; скорость, направление, смещение, яркость | Дизайн собственных режимов и превью. Их renderer напрямую пишет framebuffer и не содержит готового handoff нашему Quasar |
| Светомузыка | Android `Visualizer(0)`, 24 сглаженные полосы FFT, отправка по локальному UDP | Лучший кандидат для отдельной проверки спектра нашего штатного плеера; не требует сразу менять плеер |
| Автояркость | Датчик освещения, нижняя/верхняя границы, плавное изменение | Следующий кандидат после постоянного диммера; доступ к датчику на нашей прошивке ещё нужно проверить |
| Обнаружение оборудования | Поиск framebuffer по имени и проверка fallback; чтение геометрии и RGB offsets через ioctl | Полезный образец проверки совместимости вместо доверия номеру устройства |
| Производительность | Событийное пробуждение renderer, разные интервалы для статичного и анимированного состояния; ограничение аудиоочередей и таймауты слушания | Самостоятельно применять подходы там, где наши измерения показывают задержку |
| Home Assistant | MQTT discovery и Wyoming satellite | Возможный отдельный адаптер. Wyoming использует внешний HA pipeline и добавляет серверную зависимость |

Источники: [настройки кольца](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/config.hpp#L25-L37),
[аппаратный renderer](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/hw/framebuffer_hw.cpp),
[Android Visualizer](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/launcher/app/src/main/java/com/openstation/launcher/StationAudioService.kt#L943),
[MQTT discovery](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/net/mqtt_client.cpp#L235-L327),
[Wyoming](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/wyoming/wyoming_server.cpp).

### Важное про fb4/fb5

README называет кольцом `fb4`, экраном `fb5`. У нас проверено обратное:
`fb5` — `YNDX LED ring`. Однако основной daemon OPENSTATION ищет устройство
по имени и проверяет fallback; алгоритм потенциально найдёт и нашу раскладку.
Это не доказательство совместимости всего daemon.
[Поиск и проверка](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/hw/framebuffer_hw.cpp#L563-L630).

### Важное про светомузыку

В качестве fallback проект также анализирует собственный микрофонный PCM.
Поэтому наличие движущегося кольца само по себе не подтверждает цифровой
спектр музыки. Для нашего будущего теста нужно отличить музыку от тишины,
паузы и собственного голоса; проверить output mix/native player/offload.
Даже успешный Visualizer не решает вопрос, кто пишет RGB-кадры кольца.
[Приём спектра](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/main.cpp#L5835-L5933).

## Что нельзя считать готовым решением наших задач

- **Голос без ПК:** локально работают собственный захват, KWS и VAD, основной
  диалог/речь обслуживает Gemini Live. У нас уже есть штатный wakeword и локальное
  ASR; замена всей цепочки ради подсветки не нужна.
  [Настройка Live](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/assistant/assistant_manager.cpp#L1215-L1227).
- **Доступ без сетевого посредника:** в проекте используется внешний Comss
  SNI-fallback. Россия отсутствует в актуальном
  [официальном списке регионов Gemini API](https://aistudio.google.com/docs/available-regions).
  Это другая внешняя зависимость, а не подтверждение прямой работы Google из нашей сети.
- **Блокировка штатных OTA:** отключение сервисов в другой прошивке и собственные
  обновления GitHub не заменяют нашу сетевую защиту сохранённого native Quasar.
- **Установка одного APK для кольца:** hardware module работает с framebuffer,
  ALSA/I²C и настройками усилителей. Его целиком нельзя подключить как узкий
  setter яркости к нашему Agent.

## Почему проверяли исходники, а не только README

README говорит ExoPlayer, но актуальный `YandexMusicPlayer` использует
`android.media.MediaPlayer`. Он также заявляет отключённые произвольные команды
ассистента, тогда как текущий default `agent_mode=true`, migration в main включает
его, а setup экспортирует `shell_exec`/`adb_exec`. Это не функции, которые следует
переносить в наш голосовой обработчик.
[Конфигурация](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/config.hpp#L129),
[migration](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/main.cpp#L1292-L1298),
[tools](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/assistant/assistant_manager.cpp#L1171-L1188).

В текущем HTTP/WS сервере не найден общий механизм аутентификации API;
он слушает `INADDR_ANY`. Старый `SECURITY_FIXES.md` описывает другую ревизию
с token-auth и не подтверждает текущую реализацию.
[Listener](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/web/http_ws_server.cpp#L140-L155),
[dispatch](https://github.com/Olegu621/OPENSTATION/blob/be24e697896fedf556a246c6ee951e7d55c21393/daemon/src/web/http_ws_server.cpp#L563-L577).
Это статический вывод о просмотренных путях, не проверка эксплуатации.

Общей лицензии основного проекта в дереве не найдено; есть отдельные лицензии
компонентов. Для дословного переноса кода и ассетов в наш публичный monorepo нужно
прояснить разрешения. Публичность репозитория сама по себе не заменяет лицензию:
[пояснение GitHub](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository).

## Рекомендуемый порядок

1. Завершить узкий постоянный диммер через уже подтверждённый нами
   [SystemControl HIDL](ring-systemcontrol.md), с честным статусом применения.
2. Изолированно проверить Visualizer и цифровой спектр штатного плеера.
3. Решить управление RGB с приоритетом микрофонов, голоса, таймера и ошибок;
   затем добавлять свои режимы и их превью в пульт.
4. Рассматривать полную замену прошивки только как отдельный проект миграции.
