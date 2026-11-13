# Зависимости и происхождение

Бинарные зависимости загружаются у издателей отдельно. Их SHA-256, размеры и URL зафиксированы в `config/asr-dependencies.json`, `config/sherpa-downloads.json`, `config/r8-download.json`; downloader сверяет хеш до распаковки. Архивы распаковываются только по разрешённым именам файлов.

| Компонент | Версия / источник | Назначение |
|---|---|---|
| [Sherpa ONNX](https://github.com/k2-fsa/sherpa-onnx) | `v2.0.1`, Apache-2.0 | JNI + Java API распознавания |
| [Русская streaming Zipformer модель](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16) | revision `31fa603e4f31279c6e1f7600fed13dc4312663ab` | Offline ASR; encoder/decoder/joiner/tokens |
| [Vosk Android](https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/0.3.75/) | `0.3.75`, Apache-2.0 | Сохранённый альтернативный recognizer / совместимость |
| [JNA](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.18.1/) | `5.18.1`, Apache-2.0 / LGPL | Vosk JNI binding |
| [R8/D8](https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.5.32/r8-9.5.32.jar) | `9.5.32` | Dex для Voice с современными библиотеками |
| [Android SDK](https://developer.android.com/tools) | Platform 28, Windows Build Tools 36.0.0 | aapt2, zipalign, apksigner, Android stubs |
| [JSON-java](https://repo.maven.apache.org/maven2/org/json/json/20240303/) | `20240303` | Только синтетические JVM-тесты |
| [Tftpd64](https://pjo2.github.io/tftpd64/) | Portable `4.74` | Только DHCP, без TFTP/DNS/SNTP/Syslog |
| [cryptography](https://cryptography.io/) | Локально проверена `46.0.5` | AES-GCM/PBKDF2 личной резервной копии |

Лицензии используемых ASR-компонентов и fixtures сохранены в `apps/voicebridge/assets`. Публичный `russian_trusted_bundle.pem` содержит только сертификаты центров сертификации, без приватных ключей. Synthetic audio fixtures предназначены для тестов. Иконки/изображения интерфейса и материалы тем описаны в README/документации форка; права на сторонние продукты сохраняются у их владельцев.

## Импорты PC

Активная цепочка: `start.py` → `tunnel_only_supervisor.py` → `runtime_supervisor.py` → `giga_tunnel.py`, `windows_physical_source.py`, `native_config_window.py` → ACL-утилиты `setup_speechify.py`; отдельно `panel_relay.py`, `probe_local_ota.py`. DNS/NTP и opaque native relay — `LocalConfigServer.py` + `StationDisplayOverlay.py`. Все runtime-модули используют Python stdlib; `cryptography` требуется только резервному копированию и enrollment пинов.

`pc/reference/*.reference.ps1.txt` — обезличенные исторические скрипты для аудита логики старого стенда. Это не точки запуска переносимого пакета. Старые PC ASR/GigaChat/music/display business-серверы не нужны в текущем tunnel-only режиме и в установку не входят.
