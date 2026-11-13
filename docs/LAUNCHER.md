# Лаунчер Станции Макс

Используется отдельный [форк CleanYourTV пользователя B-a-t-0-n](https://github.com/B-a-t-0-n/cleanyourtv/tree/station/library-focus-layout). Код лаунчера и темы остаются в нём; в Station Suite не копируются исходники vendor-прошивки и не добавляется git submodule.

## Зафиксированная версия

| Параметр | Значение |
| --- | --- |
| Репозиторий | `https://github.com/B-a-t-0-n/cleanyourtv.git` |
| Ветка | `station/library-focus-layout` |
| Коммит | [`080ca9bd47da702349dc39fc266cadb7d1b45d0a`](https://github.com/B-a-t-0-n/cleanyourtv/commit/080ca9bd47da702349dc39fc266cadb7d1b45d0a) |
| Пакет / Home activity | `com.gathin.tvhome` / `.MainActivity` |
| Версия APK | `3.4`, versionCode `13` |
| Android | minSdk `26`, targetSdk / compileSdk `34`; проверен на Android 9 Станции Макс |
| Установленный артефакт | `cleanyourtv-theme-gallery.apk`, **600549 байт** |
| SHA-256 установленного APK | `8ffb7ac1e15f54fd900627a66de8f8ecbac1df1c81a217682797421049b39616` |
| SHA-256 сертификата APK | `62936e93982be6fd12cdd3e7fca38a21cff05f631cf424cb511fabb59f6df1b1` |

Ветка может двигаться. Для восстановления этой версии нужен полный коммит выше, а не произвольный свежий `main`.

Изменения форка: устранение лишних layout при перемещении фокуса, локальный масштаб приложения, офлайн-каталог и галерея тем с отдельной кнопкой применения. Просмотр превью не меняет активную тему. Плашка с домиком называется «Домой» и открывает настройки главного экрана. Штатная Алиса остаётся отдельным приложением.

## Сборка из исходников

Параметры взяты из [Gradle-файлов точного коммита](https://github.com/B-a-t-0-n/cleanyourtv/tree/080ca9bd47da702349dc39fc266cadb7d1b45d0a/launcher), не из текущих рекомендаций другой ветки.

| Компонент | Версия |
| --- | --- |
| Gradle Wrapper | `8.9` |
| SHA-256 дистрибутива Gradle | `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab` |
| Android Gradle Plugin | `8.5.2` |
| Проверенный JDK | Microsoft OpenJDK `21.0.8+9-LTS`; исходный код компилируется с Java 17 |
| Android SDK Platform | `platforms;android-34`, проверена revision `3` |
| Android Build Tools | `34.0.0` |
| Прямые зависимости APK | `androidx.recyclerview:recyclerview:1.3.2`, `androidx.dynamicanimation:dynamicanimation:1.0.0` |
| JVM-тесты | `junit:junit:4.13.2` |
| Упаковка тем | Python 3, только стандартная библиотека; проверена Python 3.13 |

Upstream рекомендует JDK 17. Приведённая проверка исторического артефакта выполнена на указанном JDK 21. Android SDK, JDK, Gradle-кеш и ключ подписи устанавливаются отдельно; они не входят в Station Suite. Репозитории зависимостей — Google Maven и Maven Central, для плагинов также Gradle Plugin Portal. Файлов dependency locking/verification в этой версии нет: это проверенная сборка с закреплёнными версиями, а не полностью герметичный toolchain.

Пример для PowerShell. Сначала установите JDK и Android SDK; пути ниже замените своими. `sdkmanager` должен установить `platforms;android-34`, `build-tools;34.0.0` и `platform-tools`. При первом запуске потребуются скачивание зависимостей и принятые условия Android SDK.

```powershell
git clone --single-branch --branch station/library-focus-layout https://github.com/B-a-t-0-n/cleanyourtv.git cleanyourtv-station
git -C cleanyourtv-station checkout --detach 080ca9bd47da702349dc39fc266cadb7d1b45d0a
git -C cleanyourtv-station rev-parse HEAD

# Новая чистая папка без локальных настроек и результатов прежних сборок.
git -C cleanyourtv-station archive --format=zip --output=../cleanyourtv-080ca9b.zip 080ca9bd47da702349dc39fc266cadb7d1b45d0a launcher themes tools/package-offline-themes.py LICENSE
Expand-Archive .\cleanyourtv-080ca9b.zip .\cleanyourtv-build-080ca9b
Set-Location .\cleanyourtv-build-080ca9b

$env:JAVA_HOME = 'C:\path\to\jdk-21.0.8'
$env:ANDROID_HOME = 'C:\Android\Sdk'
Push-Location .\launcher
.\gradlew.bat --no-daemon --console=plain testReleaseUnitTest lintRelease assembleRelease
Pop-Location
```

Результат: `launcher/app/build/outputs/apk/release/app-release.apk`. После первого успешного скачивания `--offline` позволяет проверить сборку с имеющимся кешем. Не подменяйте `launcherApplicationId`: тема, Home component и приведённые пути относятся к `com.gathin.tvhome`.

### Насколько APK воспроизводится

13 ноября 2026 года выполнена новая сборка из `git archive` указанного коммита: **322 теста, 0 failures/errors; lint — 0 ошибок; release собран**. Все 53 Gradle-задачи выполнены в новой папке. Все **70 ZIP entries APK побайтно совпали** с установленным артефактом; ZIP metadata и блок подписи APK v2 тоже совпали при использовании прежнего локального ключа.

Полный новый SHA-256 — `5a5e49ff52a105ed303bdfa36c75caca4487744eb1991b02b7aebfe8fd83d09f`, а не SHA установленного APK. Единственное различие находится в dependency-info блоке `0x504b4453` контейнера APK. AGP добавляет туда зашифрованные сведения о зависимостях; это описано в [документации Android](https://developer.android.com/build/dependencies#dependency-info-play). Поэтому **код и ресурсы воспроизведены, побайтовая воспроизводимость всего подписанного APK не заявляется**.

Release-конфигурация форка использует `signingConfigs.debug`. На другом компьютере будет свой debug-сертификат; без прежнего закрытого ключа нельзя получить прежнюю подпись или обновить уже установленный пакет. Ключ не публикуется. Сборка для первой установки на новом устройстве может иметь собственную подпись. При несовпадении подписи на работающей Станции следует сохранить установленный Home и данные, а не удалять пакет ради обхода ошибки.

Размер этой версии превышает upstream-лимит **589824 байт на 10725 байт**. Gate и upstream CI не менялись; успешные тесты и сборка не означают прохождения ограничения размера.

## Отдельный пакет всех тем

APK не содержит большой набор обоев и не имеет разрешения `INTERNET`. Все необходимые темы закреплены в [дереве `themes/` того же коммита](https://github.com/B-a-t-0-n/cleanyourtv/tree/080ca9bd47da702349dc39fc266cadb7d1b45d0a/themes). Их сборка не требует Android SDK, сетевых сервисов или повторной генерации картинок:

```powershell
# Из cleanyourtv-build-080ca9b; папка назначения ещё не должна существовать.
python .\tools\package-offline-themes.py --output ..\cleanyourtv-offline-pack
```

Получается **29 тем**, включая Stockholm, **124 картинки обоев**, превью, подписи и credits. Пакет содержит 216 файлов полезной нагрузки суммарно **23115955 байт**, а также `theme-package-manifest.json`; всего 217 файлов. Новый пакет сравнен с ранее подготовленным: все файлы, включая manifest, совпали побайтно. SHA-256 manifest:

```text
2f6ffc0035dcd20bca4d1ecb60458ee643d0808fddcaa4ac5631ba2aa8ec1dce
```

Manifest содержит длину и SHA-256 каждого файла. Проверяйте его содержимое, а не SHA произвольно созданного ZIP: архивные даты и способ сжатия могут отличаться. Полный контракт каталога и восстановления прежнего оформления описан в [`docs/offline-themes.md` форка](https://github.com/B-a-t-0-n/cleanyourtv/blob/080ca9bd47da702349dc39fc266cadb7d1b45d0a/docs/offline-themes.md).

## Первичная установка и масштаб

Команды ниже — пример **первичной настройки нового лаунчера** на Android 9. `<station-adb>` — выбранный USB serial или адрес уже разрешённого ADB-подключения. Они не отключают штатные пакеты. Перед обновлением существующей установки нужны её копия данных и совпадающая подпись.

```powershell
adb -s <station-adb> install -r .\launcher\app\build\outputs\apk\release\app-release.apk
adb -s <station-adb> shell am start -n com.gathin.tvhome/.MainActivity
adb -s <station-adb> push ..\cleanyourtv-offline-pack\themes /sdcard/Android/data/com.gathin.tvhome/files/
```

Для масштаба как на проверенной Станции в **корневом** `files/config.json` используется `ui_scale_percent: 185`, при этом системная плотность остаётся штатной. Это локальный масштаб лаунчера; `wm density` для него не нужен. Минимальный публичный профиль первой установки:

```json
{
  "ui_scale_percent": 185,
  "columns": 5,
  "rows_below_dock": 0,
  "app_library": true,
  "library_button": true,
  "show_library_pill": false,
  "show_inputs_pill": false,
  "show_network_pill": true,
  "launcher_settings_button": true
}
```

Сохраните его на ПК как `station-launcher-profile/config.json`. Для уже настроенного устройства эти ключи следует объединить с текущим профилем, сохранив приложения, подписи, сортировку и оформление. Не копируйте личный профиль другого устройства.

```powershell
# Только для нового профиля; существующий config.json эта команда заменяет.
adb -s <station-adb> push .\station-launcher-profile\config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json
adb -s <station-adb> shell am force-stop com.gathin.tvhome
adb -s <station-adb> shell am start -n com.gathin.tvhome/.MainActivity
```

После проверки экрана, управления и галереи Home можно выбрать лаунчер главным экраном штатным способом или `cmd package set-home-activity com.gathin.tvhome/.MainActivity`. Штатную Алису и системные службы для этого удалять не требуется.

«Домой → Темы · офлайн» открывает каталог. Стрелки листают превью; применяется только отдельная кнопка. Выбор темы сохраняет масштаб, персональные приложения и действующие настройки анимации. `ui_scale_percent` отсутствует в стандартных upstream-темах намеренно: его задаёт профиль Станции. Сохранённое оформление «До выбора темы» находится в данных устройства, а не в исходниках; личные настройки конкретной установки не обещаются как часть публичного пакета.

## Лицензии

Исходный код CleanYourTV — [MIT с copyright Gathin](https://github.com/B-a-t-0-n/cleanyourtv/blob/080ca9bd47da702349dc39fc266cadb7d1b45d0a/LICENSE). Шрифт Outfit — [SIL OFL 1.1](https://github.com/B-a-t-0-n/cleanyourtv/blob/080ca9bd47da702349dc39fc266cadb7d1b45d0a/launcher/FONT-LICENSE-OFL.txt), использованные Fluent Emoji — [MIT Microsoft](https://github.com/B-a-t-0-n/cleanyourtv/blob/080ca9bd47da702349dc39fc266cadb7d1b45d0a/launcher/ICONS-LICENSE-FLUENT-EMOJI.txt).

У обоев собственные условия: сохраняйте `CREDITS.md`, подписи, ссылки на авторов и LICENSE каждой темы. Например, Stockholm — собственные программные иллюстрации, [CC0 1.0](https://github.com/B-a-t-0-n/cleanyourtv/blob/080ca9bd47da702349dc39fc266cadb7d1b45d0a/themes/stockholm/CREDITS.md); это не общее разрешение для остальных картинок. Упаковщик переносит оригинальные файлы и credits без изменения. MIT Station Suite не заменяет эти условия.
