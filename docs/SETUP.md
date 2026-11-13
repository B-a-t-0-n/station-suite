# Установка и восстановление

Этот репозиторий воспроизводит приложения и кабельный стенд для уже подготовленной Станции Макс. Проверенная основа — Android 9 / API 28, arm64, сборка `20221212`, доступный ADB и изменённое доверие штатного Quasar к используемому локальному TLS. Установка наших APK не разблокирует заводскую прошивку. Способ первичной разблокировки зависит от ревизии устройства и сюда не включён.

## Что потребуется

- Windows 10/11, отдельный Ethernet между ПК и Станцией, отдельный физический интернет-интерфейс ПК.
- Android Platform Tools, Python 3.13; для резервной копии `python -m pip install cryptography`.
- APK из Releases или собственная сборка по [BUILD.md](BUILD.md).
- Собственная закрытая резервная копия работающей Станции. Чужие аккаунты, ключи и TLS-секреты репозиторий не предоставляет.
- Tftpd64 Portable 4.74 из [проекта автора](https://pjo2.github.io/tftpd64/). В пакет не включён бинарный файл. Проверенный SHA-256 `tftpd64.exe`: `43a8a79001510fa969ae31521aef550ff4c9c7771e903792b60a499ecf48b388`.

## Резервная копия работающей системы

Архив, пароль и расшифрованные файлы должны находиться вне клона Git. Пароль храните отдельно в защищённом менеджере или файле с доступом только владельцу. CLI принимает путь к файлу пароля, содержимое в аргументы и журнал не попадает. При повторном запуске существующий архив не перезаписывается.

```powershell
python tools/private_backup.py backup --adb C:/Android/platform-tools/adb.exe --serial 192.168.77.100:5555 --archive D:/StationRecovery/station.stationbackup --password-file D:/StationRecovery/backup-password.txt --policy D:/StationPrivate/pc/config-policy.json --baseline D:/StationPrivate/pc/native-baseline.json --tls-cert D:/StationPrivate/pc/fullchain.pem --tls-key D:/StationPrivate/pc/quasar-all.key
python tools/private_backup.py verify --archive D:/StationRecovery/station.stationbackup --password-file D:/StationRecovery/backup-password.txt
```

Архив AES-256-GCM с PBKDF2-SHA256 (600 000 итераций, случайные salt/nonce) содержит только фиксированный список: Voice config/native sync/display/music, TLS P12 и его пароль, runtime preferences, PC policy/native baseline/TLS-пару. Модели, аудиозаписи, переписка, логи и музыкальный кеш не сохраняются. Настройки Agent недоступны через `run-as`, поскольку его release-приложение не `debuggable`: таймеры и настройки интерфейса при чистой установке задаются заново. Bluetooth-сопряжение телефона также выполняется заново. Собственный APK signer храните отдельно от репозитория; этот архив его не копирует.

## Чистый ПК и восстановление своей Станции

1. Установите собственные APK через `tools/install.py`. Используйте `--aapt2` для проверки package ID; `--phone-serial` задайте по `adb devices`. Установка выполняется с `-r`, без удаления данных и без factory reset.
2. Скачайте зависимости и модель: `python tools/fetch_dependencies.py`. Модель проверяется по фиксированному SHA и ревизии до установки. Затем: `python tools/install.py --adb C:/Android/platform-tools/adb.exe --station 192.168.77.100:5555 --model .deps/sherpa/model`.
3. Сначала проверьте архив командой `verify`. Затем восстановите явно: `python tools/private_backup.py restore --archive D:/StationRecovery/station.stationbackup --password-file D:/StationRecovery/backup-password.txt --private-dir D:/StationPrivate --apply`. APK Voice должен быть установлен, ADB и `run-as` должны работать. Распаковка не запускает сервисы. Файлы существующей PC-конфигурации не перезаписываются.
4. Скопируйте `config/deployment.example.json` вне Git, например в `D:/StationPrivate/deployment-input.json`. Заполните пути к инструментам, APK Agent, индексы двух физических интерфейсов, GUID выделенного Ethernet и реальную IPv4-подсеть интернет-интерфейса (`Get-NetAdapter`, `Get-NetIPAddress`). Не выбирайте домашний LAN в качестве выделенного Ethernet.
5. Запишите пины восстановленных файлов: `python tools/configure_private.py --template D:/StationPrivate/deployment-input.json --output D:/StationPrivate/deployment.json`. Это явное доверие выбранной собственной копии; новые произвольные сертификаты штатный Quasar автоматически не примет.
6. Проверьте без изменений сети: `python pc/start.py --deployment D:/StationPrivate/deployment.json --check`.
7. В PowerShell администратора: `./pc/Install-StationLab.ps1 -DeploymentFile D:/StationPrivate/deployment.json`. Скрипт проверяет GUID и отсутствие чужих адресов/шлюза, добавляет только `192.168.77.1/24` и `.2/24`, отключает forwarding на выделенном интерфейсе, создаёт правила Firewall для него. Для входа в Windows добавьте `-AtLogon`, для немедленного запуска — `-Start`.
8. Обычный запуск: `./pc/Start-StationLab.ps1 -DeploymentFile D:/StationPrivate/deployment.json`. Остановка — создать пустой файл `D:/StationPrivate/state/stop`; для следующего запуска удалить этот явный stop-файл. Сервис не включает ICS/NAT и не предоставляет колонке общий интернет.

Не запускайте portable-стенд одновременно со старым: это другие владельцы тех же портов/ADB forwards. Сначала штатно остановите старые задачи и процессы; скрипты не убивают посторонние процессы. Если UDP 123 занят Windows Time или сторонним NTP, запуск завершится ошибкой: настройте конфликт отдельно, не отключайте системные службы вслепую.

После старта проверьте пульт по Bluetooth, `http://127.0.0.1:8900/`, `/check_updates` через `pc/probe_local_ota.py`, голосовой вопрос, музыку и физический LED-дисплей. При сбое проверки OTA транспорт закрывается. Wi-Fi в проверенной кабельной схеме выключен; безопасная работа в домашнем Wi-Fi требует отдельно настроенного маршрутизатора/DNS/firewall и в эту установку не входит.
