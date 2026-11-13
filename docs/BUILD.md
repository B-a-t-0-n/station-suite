# Сборка

Сборка не обращается к устройствам и не устанавливает APK. Исходники не зависят от расположения прежнего рабочего каталога.

## Инструменты

- Windows x64, Python 3.13.
- JDK 21 для D8 и apksigner. Проверенная связка использовала `javac` JDK 8 для совместимого Java 8 bytecode; укажите `--javac-home` отдельно.
- Официальный Android SDK: Platform `android-28`, Build Tools `36.0.0` (aapt2, zipalign, apksigner, D8). Подготовьте минимальную структуру: `python tools/prepare_android.py --sdk C:/Android/sdk --build-tools 36.0.0`.
- Для Voice: `python tools/fetch_dependencies.py`. Загрузка закреплена по SHA-256 и версии. Модели и бинарные зависимости остаются в игнорируемом `.deps`; Maven JSON для host-тестов также игнорируется.
- Собственный PKCS12 signer вне клона: `station-suite.p12`, alias `station-suite`, пароль в `password.txt` в той же защищённой папке. Для обновления уже установленного приложения нужен его прежний signer. Публичный репозиторий и архив восстановления его не содержат.

Пример (пути заменяются на ваши):

```powershell
python tools/build.py --app all --java-home C:/Java/jdk-21 --javac-home C:/Java/jdk-8 --signing-dir D:/StationSigner
```

Для имеющегося signer с другим именем используйте `--keystore` и `--alias`. `--android-parts`, `--asr-deps`, `--sherpa-deps` позволяют использовать уже подготовленные локальные зависимости без повторного скачивания. Пароли в аргументы не передаются.

Результаты: `apps/phone/dist`, `apps/agent/dist`, `apps/voicebridge/dist`. Каждый build проверяет подпись, zip alignment и Android manifest, пишет локальный `BUILD-RECORD.json` с SHA-256. В Git build/dist не попадают; публикуются только проверенные собственные APK и публичные контрольные суммы.

## Проверки без устройств

```powershell
python tools/test.py --java-home C:/Java/jdk-21 --javac-home C:/Java/jdk-8
```

Тесты используют синтетические конфиги, fake ADB/сеть/облачные ответы и Android stubs. Они проверяют владение ADB-транспортом, fail-closed/lease, OTA pinning, обработку голоса/таймеров/музыки, lifecycle, настройки дисплея и зашифрованное восстановление. Это не заменяет проверку физического LED-дисплея, слышимости ответа и повторного подключения телефона на конкретной Станции.

## Обновление публичного снимка разработчиком

`tools/export_sources.py --legacy-root <исходный стенд> --voice-root <исходники Voice> --include-phone` экспортирует только разрешённые исходники/синтетические assets, переносит пути на настройки окружения и фиксирует `config/source-snapshot.json`. Он не берёт dist, private state, TLS P12, аккаунтные JSON или логи. Этот режим нужен для сопровождения данного снимка; обычному пользователю достаточно сборки из Git.
