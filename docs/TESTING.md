# Отчёт о тестировании

Окружение: Linux-контейнер, JDK 21, Gradle 8.14.3, Android SDK 36. Эмулятора и устройства нет (нет `/dev/kvm`), поэтому UI- и интеграционные тесты идут на JVM в Robolectric (API 26, 30, 34; телефон, маленький экран, планшет). Серверы Mojang, Fabric, Forge, Microsoft и GitHub из контейнера недоступны; сетевая логика проверена на MockWebServer.

## Результат последнего прогона

`./gradlew test :app:lintDebug assembleDebug assembleRelease --continue`

| Модуль | Тестов | Упало |
|---|---|---|
| core:common | 41 | 0 |
| core:data (Robolectric) | 44 | 0 |
| domain:bedrock | 38 | 0 |
| domain:java | 81 | 0 |
| app (Robolectric + Hilt) | 62 | 0 |
| **Всего** | **266** | **0** |

Android-модули прогоняются в вариантах debug и release, в таблице уникальные тесты. Lint: 0 ошибок, 43 предупреждения (в основном «есть более новая версия зависимости»). Lint не анализирует тестовые исходники: Lint 8.13 падает с внутренней ошибкой FIR на Compose-тестах. Собраны `app-debug.apk` и неподписанный `app-release-unsigned.apk` (R8).

## Обязательные проверки из ТЗ

| № | Проверка | Чем покрыто | Где |
|---|---|---|---|
| 1 | Приложение устанавливается | Сборка APK, манифест, ресурсы четырёх языков; реальная установка — только на устройстве | CI, ручная проверка |
| 2 | Приложение запускается | Robolectric запускает `MainActivity` с Hilt в каждом UI-тесте | `UiTest.kt` |
| 3 | Главный экран без критических ошибок | Рендер Home на API 26/30/34, телефон и планшет; кнопка Play без профиля | `PhoneUiTest`, `SmallOldPhoneUiTest`, `TabletUiTest` |
| 4 | Раздел Java Edition | Переход и вкладки (миры, конфиги), статус профиля | `java screen shows profile status and tabs` |
| 5 | Раздел Bedrock Edition | Переход, библиотека, карточка версии, удаление | `bedrock library card opens details…` |
| 6 | Обнаружение установленной Bedrock | Блок обнаружения рендерится; ответ `PackageManager` на реальном устройстве — ручная проверка | UI-тесты, ручная |
| 7 | Передача управления игре | Формирование launch-intent и VIEW-intent с FileProvider; запуск игры — только на устройстве | интеграционные тесты, ручная |
| 8 | Системный выбор файлов | Импорт через `content`/`file` Uri тем же кодом, что после SAF; сам системный диалог — на устройстве | `RepositoryIntegrationTest` |
| 9 | Импорт поддерживаемых форматов | APK/APKS, .mcpack, .mcaddon, .mcworld, .mcstructure, .zip, .jar, .mrpack, .eloapack, раскладки JSON | `ApkSetTest`, `AddonInspectorTest`, `ContentAndModpackTest`, `NewFeaturesIntegrationTest` |
| 10 | Резервное копирование | Миры Bedrock (.mcworld), моды, профили, дополнения | `WorldArchiveTest`, `ModsTest`, интеграционные |
| 11 | Восстановление | Миры (байт-в-байт), моды из снимка | `WorldArchiveTest`, `ModsTest` |
| 12 | Выбор Java Runtime | Требуемая версия из version.json, проверка ELF/архитектуры, импорт | `InstallAndLaunchTest`, `JavaRuntime*` |
| 13 | Метаданные версий | version_manifest_v2, version.json, наследование, правила ОС | `MetaTest` |
| 14 | Повреждённые файлы | Обрезанные и изменённые архивы, бит-флип в STORED-записи (найден и исправлен дефект CRC), неверный SHA-1, повреждённые APK, повреждённая копия мира не восстанавливается | `SafeZipTest`, `SafeZipCrcTest`, `DownloadManagerTest`, `WorldArchiveTest` |
| 15 | Нет интернета | Кэш списка версий, ошибки сети, режим Online без сети отказывает | `DownloadManagerTest`, `NewFeaturesIntegrationTest` |
| 16 | Офлайн-функции | Режим Offline выдаёт сохранённую проверенную идентичность без сети; без аккаунта — отказ | `LaunchModesTest`, `offline mode uses the stored verified identity…` |
| 17 | Безопасное хранение аккаунтов | AES-GCM, маскировка токенов в toString и логах, PKCE (state, verifier) | `SettingsAndMiscTest`, `MicrosoftAuthTest`, `PkceTest` |
| 18 | Настройки интерфейса | Переключатели и слайдеры экрана настроек, плотность и масштаб | UI-тесты |
| 19 | Переключение тем | Тёмная, светлая, системная и все акценты | `dark and light themes render`, `ComponentUiTest` |
| 20 | Поворот экрана | Пересоздание Activity в альбомной и портретной ориентации на Home, Worlds, Performance | `RotationUiTest` |

Дополнительно: миграция БД v1→v2 на реальной схеме 1.0 (`MigrationTest`), русский и немецкий интерфейс (`RussianUiTest`, `GermanUiTest`), обход всех 25 разделов на трёх конфигурациях экрана, `options.txt` для старых и новых версий, режимы производительности, оценка рендереров по GPU, раскладки управления на разных соотношениях сторон, анализатор сбоев, проверка обновлений с подменой файла.

## Дефекты, найденные тестами в этой итерации
* `SafeZip.verifyIntegrity()` не замечал изменённые байты внутри STORED-записи: `java.util.zip.ZipFile` не сверяет CRC-32. Теперь CRC и размер сверяются явно.
* `ThermalMonitor` падал при старте приложения, если в системе нет сервиса температур (`addThermalStatusListener` бросает исключение). Теперь лаунчер работает без него и показывает «не сообщается устройством».
* Слэг репозитория `../evil` проходил проверку в менеджере обновлений.

## Требует физического устройства и купленной игры
1. Установка APK лаунчера, запуск, разрешение уведомлений (Android 13+).
2. Обнаружение и запуск установленной Minecraft Bedrock; кнопка Google Play при её отсутствии.
3. Импорт своих APK Bedrock (base + split), установка через системный установщик, отказ при смене подписи.
4. «Открыть в Minecraft» для .mcpack и .mcworld; копирование в папку com.mojang, выданную через SAF.
5. Резервная копия и восстановление реального мира из `games/com.mojang/minecraftWorlds`.
6. Вход Microsoft через браузер (redirect `eloa-launcher://auth`) и по коду с собственным Azure Client ID; аккаунт с купленной Java Edition.
7. Установка профиля Fabric/Forge с реальной загрузкой файлов Mojang; пауза и возобновление.
8. Тепловой статус и уведомление о перегреве под нагрузкой.
9. Подключение Bluetooth/USB-геймпада, тест кнопок и стиков.
10. Обновление лаунчера с GitHub Releases (нужен опубликованный релиз с `.sha256` и подписью владельца).
11. Запуск Java Edition: возможен только после подключения бэкенда исполнения (см. ARCHITECTURE.md).

## Что не проверялось
* Инструментальные тесты на эмуляторе или устройстве: нет окружения.
* Работа минифицированного release-APK на устройстве.
* Производительность (FPS, время старта): не измерялась, прирост FPS не обещается.
