# Экспериментальный Wi-Fi без транспорта ПК

Для подготовленной Станции Макс / Android 9 / `20221212`. Это отдельный
`local.station.wifi`, а не системный firewall. Пользователь явно выбирает
прямой интернет **без гарантии блокировки OTA**. Root и изменение прошивки
не используются.

**Последующая проверка 16 ноября 2026 года:** после перезагрузки пользователь
сообщил о завершившемся обновлении. После factory reset, официальной проводной
настройки и одной перезагрузки ADB восстановлен; Android 9/build `20221212`,
fingerprint и хеш native `maind` совпали с сохранёнными до сброса. Установка
новой ОС/native-прошивки не подтверждена. Возможные обновления APK до сброса
теперь не установить. Безопасность VPN от OTA по-прежнему не доказана.
Suite APK были удалены сбросом и затем восстановлены вместе с приватными
настройками и Bluetooth-парой. Сейчас Wi-Fi выключен, используется
изолированный Ethernet. Приведённая ниже схема и
успешные проверки относятся к сеансу до перезагрузки и factory reset.

## Схема

```text
DNS известных служебных имён → 10.111.77.1
      VPN /32 → Java policy → hev/lwIP → loopback SOCKS
                                  ├─ config TLS :17683
                                  ├─ push TLS   :17684
                                  └─ HTTP 204   :17685

GigaChat / Speechify / каталог и аудио → домашний Wi-Fi напрямую
Bluetooth-пульт → Agent
```

DNS-сервер TUN — `10.111.0.1`; таблица локальных имён содержит и
`station-config.quasar.yandex.net`. Для локальных имён A возвращает виртуальный
IPv4, AAAA — NODATA. Известная телеметрия получает NXDOMAIN. Остальные UDP DNS
запросы отправляются на DNS физической Wi-Fi-сети; имена и ответы в журнал не
записываются. `api.lbs.yandex.net` пока не обслуживается TLS relay.

Только два IPv4 `/32` направляются в TUN. Default route и IPv6 не
перехватываются. Неизвестный TLS SNI/HTTP Host локального адреса не имеет
внешнего fallback. Виртуальный адрес отличается от адреса ПК. Agent,
Voicebridge, shell и сам модуль исключены из VPN.

Модуль держит явный `requestNetwork(WIFI, INTERNET)`: на этой прошивке при
подключённом Ethernet без такого запроса Wi-Fi мог отвергать ручной connect
с сообщением `CMD_START_CONNECT but no requests`. Voicebridge в direct-режиме
отдельно выбирает Wi-Fi только для собственного процесса.

## Подготовка и запуск

Описанные шаги сохраняются для исследования; они не являются инструкцией
по гарантированному предотвращению OTA.

Сначала нужны работающие локальные службы Voicebridge, приватные config/TLS
материалы и модель ASR. Модуль их не создаёт и не заменяет. Сертификат должен
приниматься native-клиентом. Ключи и Wi-Fi-пароль остаются в приватном профиле.

1. Обновить Voicebridge до исходников 0.22. В его приватном `config.json`
   поставить `lab_proxy_port: 0`, в `music-config.json` — `transport: "direct"`.
   [Ограничения прямого музыкального транспорта](../../docs/research/music-direct-transport.md).
2. Сохранить конфиги для отката. Остановить PC tunnel owner; исключить запуск
   старой задачи, принудительно выключающей Wi-Fi. Её прежний зелёный статус
   не относится к новому режиму.
3. Установить собственную сборку модуля и разрешить VPN. Сначала проверить
   ограниченный запуск, сохранить независимый канал управления и откат.
4. Выбрать домашнюю Wi-Fi-сеть. При наличии Ethernet проверить отдельный
   Wi-Fi IP, веб-панель Agent и новое ADB-подключение к этому IP.
5. Проверить DNS, HTTP204 и локальный TLS config под UID Quasar, затем
   отключить кабель ПК, проверить ответ голосом, свежую музыку и настройки.
6. Кнопка «Включить локальные сервисы» выбирает постоянный экспериментальный
   режим. В нём нет тестового deadline; preference запрашивает запуск после
   `BOOT_COMPLETED`, но не обеспечивает перехват до старта native updater.
   Кнопка выключения снимает preference и останавливает VPN.

Shell-only `ControlActivity` защищена `DUMP`. Команды `start`/`stop` не
принимают произвольные адреса; `durationMs=1000..3600000` ограничивает тест,
`0` — явный постоянный режим. `join` читает приватный одноразовый JSON и
удаляет его; SSID/PSK не передаются в intent. Пароль не включается в APK.

Команда `prepare` отдельно открывает системное согласие Android на VPN и
сохраняет `consent_ready` или `consent_declined` в `control-status.json`.
Она не запускает VPN, не меняет preference автозапуска и не подключает
Wi-Fi. Это позволяет сначала получить согласие, а ограниченную пробу
включить позднее через `start` с ненулевым `durationMs`. Новый запрос
согласия удаляет прежний `control-status.json`, чтобы не выдавать старый
результат за текущее решение пользователя.

Нативный worker работает в отдельном процессе `:network`. При остановке TUN
закрывается, локальные сокеты снимаются, stop/join ограничен независимым
трёхсекундным завершением этого процесса. Always-on и lockdown не включаются.

## Ограничения

- Уже доказан [системный обход обычного VPN](../../docs/research/vpn-system-bypass.md).
  Прямой IP, отдельный DNS, IPv6 и запрос до запуска приложения могут пройти
  в интернет. Ответ `hasUpdate:false` не означает системный запрет установки.
- Нет обещания защиты при boot, crash или конкуренции с другим VPN. Happ
  и этот модуль не могут одновременно владеть Android VpnService.
- Проверен отдельный диагностический процесс UID Quasar; это не атрибуция
  каждого сокета native updatesd.
- Пока поддержан UDP DNS; TCP DNS fallback и общий IPv6 relay отсутствуют.
- Таблица TCP ограничена 128 tuples с 10-минутным сроком неактивности;
  после FIN в обе стороны запись живёт ещё 5 секунд, после RST — 1 секунду.
  Длительный нагрузочный тест ещё требуется. Локальные TLS потоки имеют
  лимиты, после истечения idle timeout push должен переподключаться.
- Shell input helper после холодной загрузки пока требует отдельного ADB
  bootstrap. Bluetooth HID работает независимо от него. Полную автономность
  всех команд после reboot нельзя выводить из успешного текущего сеанса.

## Сборка и проверки

Сначала собрать API28 native stack по [исходному lock](../native-tun2socks/README.md).
Затем использовать `build.py` с явными `--java-home`, `--javac-home`,
`--android-parts` и внешним `--signing-dir`; формат как у
[исходной relay-пробы](../vpn-relay/README.md). Сборщик проверяет хеш native
библиотеки и включает notice/source bundle. Он не скачивает зависимости,
не создаёт signer, не устанавливает APK и не подключается к Станции.

```console
python -m unittest discover -s experimental/wifi-standalone/tests -p "test_*.py" -v
```

Host-тесты проверяют реальные DNS/TCP пакеты, направления, TTL таблицы,
подменённые DNS-ответы, ограничения и malformed input. Они не заменяют тест
голоса, слышимости и холодной загрузки на устройстве. APK/native `.so` пока
остаются локальными: [вопросы публикации native binary](../native-tun2socks/NOTICE-AUDIT.md)
этот эксперимент не снимает.

### Loopback-проверка владельца TCP-сокета

`ControlActivity` поддерживает отдельную команду `socket_owner_probe` в UID
самого APK. Она требует уже выключенных Wi-Fi и VPN, не запускает VPN,
не меняет профиль или автозапуск. Единственное соединение:
`127.0.0.1:24110 → 127.0.0.1:24111`, без отправки payload. Клиент
[StationSocketOwnerClient.java](tests/device/StationSocketOwnerClient.java)
проверяет свой UID, повторяет connect до 4 секунд, держит соединение до EOF
не более 3 секунд и принудительно завершает процесс через 7 секунд.

Сборка клиента из корня репозитория, PowerShell. `JAVAC_HOME` указывает на
JDK 8, `JAVA_HOME` — на JDK для D8, `ANDROID_PARTS` — на локальные
`platform/android.jar` и `build-tools/d8.jar`. Команды создают только
выходные файлы в фиксированном относительном каталоге; APK не собирается
и ничего на устройство автоматически не устанавливается.

```powershell
$ownerBuild = 'experimental/wifi-standalone/build/socket-owner-client'
New-Item -ItemType Directory -Force -Path "$ownerBuild/classes", "$ownerBuild/dex" | Out-Null
& "$env:JAVAC_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -cp "$env:ANDROID_PARTS/platform/android.jar" -d "$ownerBuild/classes" experimental/wifi-standalone/tests/device/StationSocketOwnerClient.java
if ($LASTEXITCODE -ne 0) { throw 'Client javac failed' }
& "$env:JAVAC_HOME/bin/jar" cf "$ownerBuild/client-classes.jar" -C "$ownerBuild/classes" .
if ($LASTEXITCODE -ne 0) { throw 'Client jar failed' }
& "$env:JAVA_HOME/bin/java" -cp "$env:ANDROID_PARTS/build-tools/d8.jar" com.android.tools.r8.D8 --release --min-api 28 --lib "$env:ANDROID_PARTS/platform/android.jar" --output "$ownerBuild/dex" "$ownerBuild/client-classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'Client D8 failed' }
```

Следующий ручной запуск предназначен для нашей подготовленной Станции:
`ru.yandex.quasar.app` имеет UID **10005** и разрешает `run-as`.
Это не переносимое значение UID для других устройств. Сначала проверить,
что Wi-Fi и VPN уже выключены, затем явно выбрать ADB serial через
`STATION_ADB_SERIAL`. Сервер пишет результат атомарно; каждый запуск требует
новый nonce из 32 строчных hex-символов. Читать нужно сначала `listening`,
а после клиента — свежий окончательный результат с тем же nonce.

```powershell
if (-not $env:STATION_ADB_SERIAL) { throw 'Select STATION_ADB_SERIAL first' }
$ownerNonce = [guid]::NewGuid().ToString('N')
$ownerRemote = "/data/local/tmp/station-owner-client-$ownerNonce.dex"
adb -s "$env:STATION_ADB_SERIAL" push "$ownerBuild/dex/classes.dex" "$ownerRemote"
if ($LASTEXITCODE -ne 0) { throw 'Client push failed' }
adb -s "$env:STATION_ADB_SERIAL" shell am start -W -n local.station.wifi/.ControlActivity --es command socket_owner_probe --es nonce "$ownerNonce"
$ownerReady = adb -s "$env:STATION_ADB_SERIAL" shell run-as local.station.wifi cat files/socket-owner-probe.json | ConvertFrom-Json
if ($ownerReady.nonce -ne $ownerNonce -or $ownerReady.status -ne 'listening') { throw 'Fresh listener not ready; do not run client' }
adb -s "$env:STATION_ADB_SERIAL" shell run-as ru.yandex.quasar.app /system/bin/toybox env "CLASSPATH=$ownerRemote" /system/bin/app_process /system/bin local.station.socketprobe.StationSocketOwnerClient 10005
if ($LASTEXITCODE -ne 0) { throw 'Client failed' }
$ownerFinalDeadline = [DateTime]::UtcNow.AddSeconds(1)
do {
    $ownerResult = adb -s "$env:STATION_ADB_SERIAL" shell run-as local.station.wifi cat files/socket-owner-probe.json | ConvertFrom-Json
    if ($ownerResult.nonce -ne $ownerNonce) { throw 'Result nonce mismatch' }
    if ($ownerResult.status -ne 'listening') { break }
    Start-Sleep -Milliseconds 30
} while ([DateTime]::UtcNow -lt $ownerFinalDeadline)
if ($ownerResult.nonce -ne $ownerNonce -or $ownerResult.status -ne 'observed') { throw 'Fresh final result required' }
$ownerResult
adb -s "$env:STATION_ADB_SERIAL" shell rm -f "$ownerRemote"
```

При ошибке nonce или `listening` результат не засчитывать; повторять только
новым запуском после завершения предыдущей пробы. APK сам ограничивает
пробу 3,8 секунды. Успех требует `lookupComplete`, `clientTupleFound`,
`clientUidMatchesExpected`, `serverTupleFound`, `serverUidMatchesOwn` и
отсутствия неоднозначности обоих UID. 18 ноября actual APK UID 10040
увидел Quasar UID 10005 и собственный принятый сокет. Проверенные SHA-256:
исходник клиента `1d971c636bbe9a9f64213209c97d9e68f03b71fd08d1dd8ad19ee559f4419d05`,
его DEX `78e7cbdfadb7295dec9c9e220e38cc003d86985b3d4f2acbb5a8797342073c71`,
APK `eccb5f693c7b318382b17f572e879e370391cedfb90320267046d725c765550a`.
Другой toolchain может дать иной DEX. Проверка доказывает чтение владельца
установленного loopback TCP-соединения; определение UID на стадии SYN,
безопасное разрешение TV-трафика и блокировку OTA она не доказывает.

## Проверка на устройстве

18 ноября 2026 года после восстановления приложений выполнена отдельная
ограниченная проба с предварительным IPv4-запретом на домашнем роутере.
Локальные DNS/HTTP204/TLS config проверены диагностическим процессом под
UID Quasar, внешняя синтетическая цепочка — процессом Voicebridge через
три точных разрешения API. После пробы VPN и Wi-Fi выключены, разрешения
удалены, проводная схема восстановлена. Ethernet оставался каналом управления.
Покрытие native updater и холодный автономный запуск не доказаны.
[Условия и результат](../../docs/research/router-rv6699.md#локальные-службы-и-внешние-api-вместе).

Позднее в тот же день дважды проверен reboot с заранее включённым Wi-Fi,
но уже без этого VPN: внешние TCP/UDP закрывал роутер. Сеть подключилась
сама, контрольные внешние соединения оставались заблокированными. Свежий
автозапуск служб подтверждён при остановленных PC business services.
Local services были `healthy:true`, voice listener запущен, но полный
voice runtime оставался `healthy:false`; обнаружено неверное время до
восстановления ПК. Полный голос без ПК,
конкретные OTA-сокеты и отключение питания этими тестами не проверены.
После них Wi-Fi выключен, проводная схема восстановлена.
[Отчёт о перезапусках](../../docs/research/results/router-wifi-reboot-2026-11-18.json).

16 ноября 2026 года, до перезагрузки: после отключения кабеля и остановки транспорта ПК
проверены Agent HTTP200, локальные DNS/HTTP204/config TLS под UID Quasar
и свежий поиск музыки. Пользователь подтвердил ответ на вопрос и запуск песни
через слово «Алиса». [Сокращённый результат](results/device-session-20221212.json).
Это свидетельства предыдущего сеанса. После перезагрузки пользователь
сообщил о завершившемся обновлении (`user_reported`). Последующее чтение после
сброса и восстановления ADB показало native версию
`1.56.1.34.1553780878.20221212.22`, системный Quasar `2.0`/code2 и прежний
Android build. SHA256 `/system/vendor/quasar/maind` —
`08a0050f4ad26e1054321c46d2e3631a6b02805e5e0355c10cbcbf53ea82ea6d` —
совпал с сохранённым до сброса. Новая ОС/native-прошивка этим не подтверждается;
это также не доказывает отсутствие любых обновлений APK до сброса.
После сброса приложения и рабочая проводная схема Suite восстановлены.
Без журнала конкретного OTA нельзя установить тип сообщения об обновлении
или связать потерю доступа со стартовым промежутком либо системным обходом.
