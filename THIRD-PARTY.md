# Сторонние компоненты и материалы

Лицензия MIT в корне относится к собственному коду Station Suite. Она не меняет условия сторонних библиотек, моделей, изображений, приложений и сервисов.

| Материал | Источник и условия |
| --- | --- |
| CleanYourTV | [Форк пользователя](https://github.com/B-a-t-0-n/cleanyourtv/tree/station/library-focus-layout); лицензия и история исходного проекта сохраняются в его репозитории |
| Sherpa ONNX | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0; runtime и модель устанавливаются отдельно, конкретные версии закрепляются в инструкции сборки |
| ONNX Runtime | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime), MIT; входит в зависимости Sherpa |
| Android SDK / build tools / platform tools | [Android SDK](https://developer.android.com/studio), условия Android SDK; не перепубликуются |
| JDK | Условия выбранного дистрибутива Java; требуется отдельно |
| Python и библиотеки ПК | [Python](https://www.python.org/), [cryptography](https://github.com/pyca/cryptography), [requests](https://github.com/psf/requests); версии и установка — в docs/DEPENDENCIES.md |
| Фото Станции Макс | [Официальная страница характеристик](https://alice.yandex.ru/support/ru/station/meet/characteristics-max), [файл изображения](https://cdn-viewer.diplodoc.com/docs-assets/alice/rev/r22105055/ru/station/_assets/compare/station-max-grey.png). © Яндекс / правообладатели. Используется для обозначения управляемого устройства; не распространяется под MIT |
| Обложка README и пиктограммы пульта | Собственная графика проекта; MIT, кроме отмеченного фото устройства |
| GigaChat / Speechify | Внешние API, собственные условия использования и квоты; пользователь предоставляет свои реквизиты |

Бинарник `maind`, прошивка Яндекса и извлечённые ресурсы закрытых приложений не входят в репозиторий. Названия Яндекс, Алиса и Станция — обозначения соответствующих правообладателей. Проект не связан с Яндексом официально.

## Тестовое аудио и сигнал таймера

- `apps/agent/assets/schedule-signal.wav` — собственный трёхсекундный сигнал из математически синтезированных синусоид, без записи микрофона, музыки или TTS-сервиса. `schedule-alert.gif` и `schedule-clear.gif` — собственные конечные изображения LED-матрицы 25×16, нарисованные программно. Воспроизведение алгоритмов в памяти дало точное побайтовое совпадение со всеми тремя опубликованными файлами. Переносимые генераторы: [make_signal.py](tools/fixtures/make_signal.py) и [make_led_signal.py](tools/fixtures/make_led_signal.py). Этот собственный код и эти материалы распространяются под MIT проекта.
- `apps/voicebridge/assets/legacy_reply_fixture.ogg` — синтетический ответ цепочки GigaChat → Speechify. Происхождение подтверждено совпадением SHA256 с сохранённым результатом отдельной синтетической проверки; её отчёт фиксирует `syntheticTextOnly=true`, `syntheticFixtureOnly=true`, `microphoneCaptured=false`, `audioPlayed=false`. Это не запись пользователя или извлечённый ресурс прошивки. Условия синтезированного сервисом аудио определяются Speechify; лицензия MIT исходного кода не переопределяет условия сервиса.
- `apps/voicebridge/assets/pipeline_fixture.wav` и производный `.ogg` — синтетическая тестовая фраза Windows SAPI / Irina; происхождение и формат записаны в `pipeline_fixture.json` и `pipeline_ogg_fixture.json`. `local-asr-fixtures/*` также сгенерированы Windows `System.Speech.Synthesis.SpeechSynthesizer`, без микрофона; это зафиксировано в `manifest.json`. Условия системных TTS-голосов остаются у их правообладателей.
- `apps/voicebridge/assets/asr_fixture.wav` — ранняя тестовая заготовка для отдельной операции `audio_probe`, не входящая в рабочий микрофонный тракт. Прежние отчёты испытаний относят этот файл к синтетическим данным: `syntheticFixtureOnly=true`, `microphoneCaptured=false`, размер 119224 байта. Точный первоначальный генератор файла в сохранённых исходниках не найден; поэтому конкретные голос, фраза и способ генерации не заявляются установленными. Это ограничение доказательства происхождения, а не подтверждение записи микрофона.

Приватные отчёты испытаний с конфигурацией устройства и пользовательскими путями в репозиторий не копируются. Наличие тестового аудио не означает предоставления голосовой модели, фирменного голоса Алисы или права использовать товарные знаки сервиса.

## ТВ-приложения

Сторонние APK не включены в выпуск Station Suite. Скачивайте их у разработчиков:

- [Okko TV](https://www.rustore.ru/catalog/app/tv.okko.androidtv)
- [VK Видео для ТВ](https://vkvideo.ru/landings/tv_instructions/index.html)
- [RUTUBE для ТВ](https://rutube.ru/androidtv/)
- [Lampa](https://github.com/lampa-app/LAMPA/releases)
- [Happ](https://github.com/Happ-proxy/happ-android/releases)
- [YouTube для ТВ](https://www.youtube.com/tv)

Совместимость конкретной версии с Android 9 и несертифицированным устройством проверяется отдельно. Установка VPN-клиента не означает, что можно включать его без потери сетевого контроля и управления Станцией.
