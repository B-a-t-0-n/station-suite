# Android 9: границы локального сетевого guard

Исследование и read-only проверка 16 ноября 2026 года, Station Max,
`PPR1.180610.011.20221212`. Домашний Wi-Fi не включался. VPN, системные
сетевые политики и роль владельца устройства в этом этапе не менялись.

## Права на нашей прошивке

Два типизированных Binder getter вызваны через отдельный `app_process`
с UID2000. Номера Binder transactions не угадывались; setters не вызывались.
В JSON сохранялись классы исключений и признаки отказа, без текста vendor
ошибок, учётных данных и содержимого трафика.

| Read-only вызов | Фактический результат |
| --- | --- |
| `IConnectivityManager.getAlwaysOnVpnPackage(0)` | `SecurityException`, отсутствует `android.permission.CONTROL_VPN` |
| `INetworkManagementService.isFirewallEnabled()` | `SecurityException`, требуется UID1000 (`AID_SYSTEM`) |
| AccountManager | 0 аккаунтов; имена и токены не извлекались |
| Device admin / owner | Feature присутствует; активного администратора, device owner или profile owner не обнаружено |
| Provisioning | `device_provisioned=1`, `user_setup_complete=1` |
| Проверка после завершения | Wi-Fi, proxy, always-on/lockdown, IPv4/IPv6 rules и PID Agent/Voicebridge/adbd/audioserver совпали с исходными |

В AOSP9 always-on getter и setter имеют два последовательных gate:
`ConnectivityService` требует `CONNECTIVITY_INTERNAL`, а `Vpn` —
`CONTROL_VPN`. Первый permission не заменяет второй. Сброс
`setAlwaysOnVpnPackage(0,null,false)` тоже меняет prepared VPN и настройки,
поэтому это не безопасный способ «просто проверить права».
[ConnectivityService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/ConnectivityService.java#3900),
[Vpn](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/connectivity/Vpn.java#449).

`NetworkManagementService` firewall methods проверяют именно UID1000,
включая getter. Наличие привилегированного manifest permission у shell не
открывает этот интерфейс. Прямой INetd имеет отдельные SELinux/DAC gates;
кроме того, Android UID child chains не следует считать универсальным
firewall для root/system: backend может освобождать системные UID.
[NetworkManagementService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/NetworkManagementService.java#2299),
[netd FirewallController](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/FirewallController.cpp).

## IPv6 отдельного интерфейса

17 ноября на этой же прошивке getter `checkUidPermission` подтвердил
`CONNECTIVITY_INTERNAL` для shell UID2000. Reflection подтвердил оба
метода `INetworkManagementService.enableIpv6(String)` / `disableIpv6(String)`,
сервис и типизированный proxy. Сетевые setters в этом проходе не вызывались;
проверяемое исходное состояние совпало после завершения.

Затем отдельная ограниченная проба при **выключенном Wi-Fi** проверила
реальные setters только для `wlan0`: `disable_ipv6` изменился с 1 на 0
и вернулся к 1. Независимый процесс повторил восстановление; Wi-Fi
остался выключен, proxy/SELinux/IPv4 rules/IPv6 rules совпали с исходными.
Временный JAR удалён. Root, включение Wi-Fi и изменения роутера не требовались.
[Результат](results/ipv6-interface-canary-2026-11-17.json).

В AOSP9 `disableIpv6` проверяет `CONNECTIVITY_INTERNAL`, без требования
UID1000; это отличается от firewall API. Однако IpClient по умолчанию
включает IPv6 при новом provisioning. Статический IPv4 сам по себе не
вызывает `withoutIPv6`. Один успешный setter не доказывает запрет до
первого подключения, при пересоздании интерфейса, reconnect или reboot.
Сохраняемого способа менять эту provisioning-конфигурацию для штатного
Wi-Fi без root пока нет. Периодический helper оставляет окно до реакции.
[NetworkManagementService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/NetworkManagementService.java#1129),
[IpClient](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/net/java/android/net/ip/IpClient.java#1363),
[WifiStateMachine](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/refs/tags/android-9.0.0_r1/service/java/com/android/server/wifi/WifiStateMachine.java#5007).

Дополнительно разобраны точные live `wifi-service.vdex`, `services.vdex`,
`boot-framework.vdex` и `wifi-events.rc` со сборки `20221212`; SHA-256
зафиксированы в [результате аудита](results/ipv6-persistence-offline-2026-11-17.json).
Этот разбор выполнялся офлайн, без сетевых setters.

- Обычный Wi-Fi запускает provisioning с IPv6 по умолчанию.
- Вызов `withoutIPv6()` найден только в `WifiP2pServiceImpl.startIpClient`,
  то есть Wi-Fi Direct; к обычной домашней сети это не относится.
- Сохраняемая `IpConfiguration` и её Wi-Fi XML-сериализатор не имеют
  параметра отключения IPv6. `IWifiManager` не предоставляет нужного setter.
- `IpClient` здесь внутренний `StateMachine`, а не доступный Binder-сервис.
  Wi-Fi shell-команды требуют root и не содержат команды управления IPv6.
- В исследованных framework-файлах не найден соответствующий постоянный
  property/settings key; `wifi-events.rc` не устанавливает такую политику.

Следовательно, временный вызов `disableIpv6(wlan0)` не превращается в
сохраняемую настройку обычного Wi-Fi. Результат ограничен перечисленными
файлами: он не исключает все неизвестные vendor-службы и не заменяет
проверку защиты при загрузке. Произвольный settings key или цикл повторного
отключения IPv6 не считаются решением раннего сетевого доступа.

## Почему не назначен Device Owner

DPC с ролью Device Owner получает штатный `DevicePolicyManager`
control-plane для always-on VPN. Это не root, UID1000 или `CAP_NET_ADMIN`.
Он не меняет исключения netd для protected/system sockets. AOSP9 допускает
некоторые варианты shell provisioning уже настроенного устройства без
AccountManager аккаунтов, но на этой Станции назначение **не выполнялось**.
[DPM VPN setter](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java#5880).

Назначение owner даже без VPN меняет системное состояние, включая backup
и ограничения пользователя. Удаление не является точным обратным действием:
в upstream removal включает backup, вместо восстановления неизвестного
исходного значения, и очищает некоторые ограничения. Удаление test-only
admin также не заменяет явное снятие always-on/lockdown перед удалением.
Поэтому это не выбранный обход ради одного сетевого теста.
[setDeviceOwner](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java#7150),
[clearDeviceOwnerLocked](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java#7399).

## Неустранённый системный обход

UID0 не исключён из обычного полного VPN автоматически: предыдущая
ограниченная `/32` проба действительно захватывала некоторые TCP-соединения
UID0. Однако в AOSP9 protected sockets и root output-interface rules могут
выбираться раньше secure-VPN и lockdown rules. Это возможности платформы,
а не доказательство их использования конкретным процессом нашей Станции.
Их нельзя закрыть одним DPC setter или положительным тестом обычного APK.
[RouteController](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/RouteController.cpp#55),
[FwmarkServer](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/FwmarkServer.cpp#248).

## Полезная часть, которую можно продолжать отдельно

[Local service relay](../../experimental/local-service-relay/README.md)
проверяет доставку служебных TCP/TLS соединений на локальные config/push
службы без PC backend. Сам по себе он не перехватывает native клиентов и
не является OTA guard.

На реальной Станции отдельный 12-секундный проход завершился успешно:
HTTP204, локальный `check_updates` с `hasUpdate:false`, TLS handshake push,
четыре отказа запрещённым направлениям и самостоятельный выход процесса.
Клиент теста работал на ПК через временный ADB forward; backend соединения
обслуживались на колонке.
[Результаты](../../experimental/local-service-relay/results/device-20221212.json).

[Native TCP stack scaffold](../../experimental/native-tun2socks/README.md)
фиксирует hev/lwIP и сборку API28/ARM64. Сборка, проверка runtime и полная
сетевая защита — разные этапы. Оба эксперимента отделены от основных APK
и автозапуска. Текущее рабочее подключение остаётся изолированным Ethernet
с ограниченным транспортом ПК.

Закреплённая сборка уже [прошла в CI](https://github.com/B-a-t-0-n/station-suite/actions/runs/43866423108):
ELF64/AArch64, Android ident API28, только `libc/libdl/libm` среди dynamic
dependencies. Сам CI публикует отчёты и не устанавливает библиотеку на Станцию.
Обычные [проверки проекта](https://github.com/B-a-t-0-n/station-suite/actions/runs/43866420025)
на этом же code commit прошли.

Затем локальная сборка под тем же API28 прошла две проверки на самой Станции:
[загрузка JNI и синтетические пакеты без VPN](../../experimental/native-tun2socks/runtime-smoke/README.md),
а затем [реальная доставка через TUN за 10 секунд](../../experimental/vpn-relay/README.md).
Контрольный HTTP204 вернулся через локальные службы; некоторые TCP443 tuples
совпали с established-сокетами UID0. Это уже работающий ограниченный transport.
Принадлежность именно updatesd, OTA URL и отсутствие системных обходов этим
не доказаны. Тестовый пакет удалён, рабочая изолированная схема сохранена.

Последующий [socket-контроль](../../experimental/vpn-relay/SOCKET-ROUTE-CANARY.md)
выявил реальный обход обычного VPN через выбор физического Network под
системным UID Quasar. Default/source-bind запросы попали в TUN, physical
Network bind вернул HTTP204 вне полного журнала TUN в активном окне.
Это не root socket execution и не доказательство OTA URL; lockdown не
включался. [Разбор системных исключений](vpn-system-bypass.md).
