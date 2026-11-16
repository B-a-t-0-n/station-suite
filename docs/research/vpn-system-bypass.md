# Почему локальный VPN ещё не защищает native OTA

16 ноября 2026 года, Station Max Android9, прошивка `20221212`.
На этой Станции реально проверен обход обычного VPN через выбор физической
сети под UID предустановленного Quasar. Это отрицательный контроль для
сетевой защиты, а не ошибка пересылки TCP/TLS внутри VPN.

## Что показала колонка

| Один запрос во время активного VPN | Результат |
| --- | --- |
| Обычный socket | HTTP204, запрос и ответ в TUN |
| `Network.bindSocket` на проверенный Ethernet Network | HTTP204 вне TUN |
| `Os.bind` только к IP-адресу Ethernet | HTTP204, запрос и ответ в TUN |
| `SO_BINDTODEVICE` на Ethernet | `EPERM`, соединение не начиналось |

Все клиенты имели UID Quasar, включённый в secure-VPN ranges. Они завершились
за первые 6,207 секунды после запуска десятисекундной пробы. Полный журнал
содержал 162 строки заголовков; вместе с семью служебными IPv6 control это
ровно 169 учтённых packet records. Таким образом, отсутствие конкретного
Network-bound tuple сопоставлено с успешным ответом и положительными
контролями захвата, а не с обрезанным logcat.

Настройки, IPv4/IPv6 rules и основные процессы после теста совпали с исходными.
Native worker завершился, независимый watchdog остановил лабораторный процесс;
APK и временные файлы удалены. Wi-Fi оставался выключенным, WAN недоступен.
[Хеши, тайминги и результаты](../../experimental/vpn-relay/results/socket-route-device-20221212.json).

## Почему номер UID больше 10000 не означает обычное приложение

Quasar имеет флаги `SYSTEM`, `PRIVILEGED`, `DEBUGGABLE`. В AOSP9
`PermissionMonitor.hasRestrictedNetworkPermission()` принимает предустановленные
системные приложения и передаёт их UID в `netd.setPermission("SYSTEM", ...)`.
Поэтому отсутствие отдельного manifest permission `CONNECTIVITY_INTERNAL`
не исключает привилегированного сетевого класса.
[PermissionMonitor](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/connectivity/PermissionMonitor.java).

`NetworkController.canProtectLocked()` допускает сетевой класс SYSTEM.
При `SELECT_NETWORK` FwmarkServer выставляет `protectedFromVpn` согласно
этой возможности. Secure-VPN rule12000 и lockdown rule12500 требуют
`protectedFromVpn=false`; отдельное root OIF rule10500 стоит ещё раньше.
[NetworkController](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/NetworkController.cpp),
[FwmarkServer](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/FwmarkServer.cpp),
[RouteController](https://android.googlesource.com/platform/system/netd/+/refs/tags/android-9.0.0_r1/server/RouteController.cpp).

Это объяснение механизма по первичному исходному коду. Значение socket mark
на устройстве не читалось. Клиент выполнялся через `run-as` под UID системного
пакета, а не под UID произвольного обычного приложения. Root-клиент не запускался:
`ip route get ... uid 0 oif eth0` был только read-only запросом маршрута.

## Почему не назначаем Device Owner ради следующего теста

Системные Settings могут предоставлять always-on и блокировку без VPN;
Device Owner открывает программный setter того же механизма. DPC не добавляет
фильтр перед перечисленными privileged routing exceptions. Upstream Vpn.java
прямо описывает системный explicit-network escape при lockdown.
[Vpn.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/services/core/java/com/android/server/connectivity/Vpn.java),
[официальное описание always-on](https://developer.android.com/develop/connectivity/vpn#always-on).

Owner provisioning меняет backup и ограничения пользователя; удаление owner
не является точным обратным действием для неизвестного исходного состояния.
Кроме того, проверенный Binder getter отказал shell из-за `CONTROL_VPN`;
исходный код требует те же привилегии для снятия lockdown, setter не вызывался.
Один watchdog, который лишь убивает VPN, не обеспечивает такой
откат. Owner, always-on и lockdown на колонке не назначались и не включались.

## Практический вывод

Пересылка native-трафика на локальные config/push службы работает. Но доступного
обычному APK способа гарантировать запрет выхода всех native/root служб до
старта VPN, после сбоя и загрузки в проверенном наборе возможностей не найдено.
Подмена DNS, глобальный proxy и расширение маршрута до `0.0.0.0/0` не устраняют
установленное исключение выбора физической сети.

Этот тест не показывает, использует ли обход конкретно updatesd, не наблюдает
OTA URL и не испытывает lockdown, глобальный IPv6 или загрузку без ПК.
Вызов найденной native-команды `check_updates` не выполнялся: его переходы
по состояниям обновления пока недостаточно изучены. Изменять политику OTA
по одному имени поля или объявлять автономный Wi-Fi безопасным оснований нет.
Текущая рабочая изолированная сеть с транспортом ПК сохраняется.
