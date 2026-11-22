# EloaLauncher — технический план и архитектура

## 1. Результаты анализа окружения (этап 1)

| Компонент | Состояние |
|---|---|
| JDK | OpenJDK 21 |
| Gradle | 8.14.3 (wrapper) |
| Android SDK | установлен вручную: platform 36, build-tools 36.0.0, platform-tools |
| Эмулятор / KVM | **нет** (`/dev/kvm` отсутствует) → инструментальные и UI-тесты выполняются на JVM через Robolectric |
| Сеть | Google Maven, Maven Central, Gradle Portal, dl.google.com — доступны. Mojang/Fabric/Forge meta API и GitHub — **заблокированы** политикой контейнера сборки (на устройстве доступны). Сетевая логика тестируется на MockWebServer. |

## 2. Выбор версий

Сознательно выбран стек, проверенный на совместимость между собой (AGP 8.13 + Kotlin 2.2 +
compileSdk 36), а не самые свежие AGP 9.x / Kotlin 2.4: AGP 9 меняет модель Kotlin-плагина
(built-in Kotlin) и требует Gradle 9, а более новые AndroidX-артефакты требуют compileSdk 37.
Переход на них — отдельная задача миграции.

| Библиотека | Версия | Лицензия |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | Apache 2.0 |
| Kotlin / Compose compiler | 2.2.21 | Apache 2.0 |
| KSP | 2.3.4 | Apache 2.0 |
| Compose BOM (Material 3) | 2025.10.01 | Apache 2.0 |
| Hilt | 2.57.2 | Apache 2.0 |
| Room | 2.8.3 | Apache 2.0 |
| DataStore | 1.1.7 | Apache 2.0 |
| WorkManager | 2.10.5 | Apache 2.0 |
| Navigation Compose | 2.9.5 | Apache 2.0 |
| kotlinx.serialization | 1.9.0 | Apache 2.0 |
| OkHttp (+MockWebServer) | 4.12.0 | Apache 2.0 |
| apksig (проверка подписей APK) | 8.13.2 | Apache 2.0 |
| JUnit 4 / MockK / Robolectric / Turbine | 4.13.2 / 1.14.6 / 4.16 / 1.2.1 | EPL / Apache 2.0 / MIT / Apache 2.0 |

minSdk = 26 (Android 8.0), targetSdk = compileSdk = 36.

## 3. Модули

```
:core:common       (Kotlin/JVM) архивы (Zip Slip, лимиты, сверка CRC-32), хеши, версии и диапазоны,
                   TOML, логирование с маскировкой секретов. Без Android-зависимостей.
:domain:bedrock    (Kotlin/JVM) AXML, анализ APK/APKS, подписи (apksig), дополнения (manifest.json,
                   UUID, зависимости), NBT, архивы миров (.mcworld: упаковка, проверка, восстановление).
:domain:java       (Kotlin/JVM) метаданные Mojang, правила библиотек, план запуска, загрузчик с
                   докачкой и SHA-1, установщики Fabric/Quilt/Forge/NeoForge, моды, options.txt,
                   режимы запуска, PKCE, каталог рендереров, раскладки управления и профили
                   геймпадов, анализатор сбоев, проверка обновлений.
:core:designsystem (Android) тема и компоненты.
:core:data         (Android) Room (схема 2, миграция 1→2), DataStore, Keystore-шифрование,
                   сведения об устройстве, режимы производительности.
:app               (Android) Hilt, навигация, экраны, ViewModel, WorkManager, PackageInstaller, SAF.
```

Зависимости идут только вниз: `app → data/designsystem → domain → common`. Вся логика, которую
можно проверить без устройства, лежит в JVM-модулях и покрыта JUnit-тестами.

## 4. Навигация и интерфейс

* Телефоны: нижняя панель (Главная, Библиотека, Моды, Миры, Аккаунты, Настройки) и меню со всеми
  разделами. 600–840 dp: боковая рейка. От 840 dp: постоянная боковая панель.
* Главная: карточки Java Edition и Bedrock Edition (версия, статус установки, Play, Settings,
  Versions), выбор режима запуска, варианты «карточки / дашборд / минимальный».
* Тексты: `i18n/strings.tsv` → `res/values{,-ru,-tr,-de}/strings.xml` (генератор
  `scripts/i18n/gen_strings.py`). Язык выбирается в настройках; на Android 13+ через системный
  per-app language API, ниже — через обёртку контекста. Вне композиции строки берёт `str()`.
* Оформление проверено по правилам unslop: ровные поверхности с тонкой границей вместо цветовых
  градиентов, один акцентный цвет, начертания 400–600, регистр предложения, отступы кратны 4 dp.
  Фирменная деталь — квадратный маркер статуса («блок»), он используется только для состояния.

## 5. Ключевые решения и ограничения

### Bedrock Edition
* Обнаружение и запуск — `PackageManager` и launch-intent пакета `com.mojang.minecraftpe`
  (видимость пакета объявлена в `<queries>`). Если игры нет — ссылка на Google Play.
* Установка своих APK — только системный `PackageInstaller`; смену подписи и даунгрейд Android
  не разрешает, лаунчер объясняет это заранее.
* Дополнения передаются игре через `ACTION_VIEW` + `FileProvider` или копируются в папку
  `com.mojang`, выданную через SAF.
* Резервные копии миров читаются только из папки, выданной через SAF. Миры во внутренней памяти
  Minecraft (`/data/data`, `Android/data` на Android 11+) недоступны — это защита системы, она не
  обходится. Копия — обычный `.mcworld`, её может импортировать сама игра. Перед восстановлением
  архив проверяется (CRC каждой записи, SHA-256, level.dat, безопасные пути), а заменяемый мир
  сначала сохраняется.

### Java Edition: бэкенд исполнения
Лаунчер готовит экземпляр полностью: метаданные, клиент, библиотеки, нативы, ассеты (SHA-1),
профили загрузчиков, `options.txt`, план запуска (classpath, аргументы JVM и игры, Quick Play для
режима локальной игры, переменные окружения выбранного рендерера). Исполнение вынесено за
интерфейс `GameRuntimeBackend`.

Как устроен Amethyst (форк PojavLauncher, LGPL-3.0): JRE, собранная под bionic, распаковывается
из APK; `libjli`/`libjvm` загружаются через JNI, `JLI_Launch` запускается в отдельном процессе;
окно и ввод дают собственный форк LWJGL 3 (GLFW заменён мостом к Android Surface) и `libpojavexec`;
OpenGL транслируется через GL4ES, LTW, MobileGlues или Zink. Все эти части — бинарные артефакты из
релизов GitHub соответствующих проектов. В окружении сборки GitHub недоступен, а проверить
нативный рендер без устройства нельзя, поэтому бэкенд в эту сборку не включён. Пока его нет, кнопка
Play выполняет все проверки и честно показывает блокирующую причину (`NO_BACKEND`).

Чтобы подключить бэкенд: реализовать `GameRuntimeBackend.launch(plan, runtime)` (процесс `:game`,
`JLI_Launch` с `plan.jvmArgs + mainClass + plan.gameArgs`, окружение `plan.environment`), положить
библиотеки рендереров и отметить их в `PerformanceViewModel.INSTALLED_RENDERERS`, связать в
`di/BackendModule.kt`. Раскладка управления для оверлея уже публикуется в `controls/active.json`.

### Аккаунты и режимы
* Вход Microsoft: authorization code + PKCE в системном браузере (redirect `eloa-launcher://auth`,
  проверка `state`, verifier только в памяти) или device code. Далее Xbox Live → XSTS →
  Minecraft Services, проверка владения игрой. Нужен Azure Client ID, одобренный Mojang; чужой ID
  не встроен.
* Токены — AES-GCM, ключ в Android Keystore, резервное копирование Android отключено.
* Режимы: Official account (обновляет сессию при наличии сети), Online (сеть обязательна),
  Offline (без сетевых запросов, только для аккаунта, уже проверенного как владелец), Local
  singleplayer (Offline + Quick Play в выбранный мир, Minecraft 1.20+). Недоступный режим
  показывается с причиной. Токены не подделываются и не генерируются.

### Ely.by
* Протокол Yggdrasil (`authserver.ely.by/auth/authenticate`, `refresh`, `validate`, `invalidate`),
  код 2FA добавляется к паролю как `пароль:код`. Пароль не хранится, в базе только
  зашифрованные accessToken/clientToken. Аккаунт хранится с ключом `ely:<uuid>`, чтобы не
  перезаписать аккаунт Microsoft с тем же UUID.
* Вход доступен только если уже добавлен аккаунт Microsoft, прошедший проверку владения игрой;
  без него Ely.by-аккаунт не даёт запуска. Так лаунчер не становится обходом покупки.
* При запуске добавляется `-javaagent:authlib-injector.jar=ely.by` (доверенный аргумент JVM, не
  из пользовательских настроек). authlib-injector скачивается с authlib-injector.yushi.moe,
  SHA-256 сверяется с опубликованным в `latest.json`; без файла запуск блокируется с причиной
  `AUTHLIB_MISSING`.

### Скины
* Модель `domain/java/skins/Skins.kt`: раскладка UV 64×64 (части тела, грани, второй слой),
  преобразование 64×32, определение тонких рук, превью спереди и сзади, заливка в пределах
  грани, скин из фото (кадр → лицо 8×8, цвет волос → макушка и бока), набор скинов Bedrock
  (`skin_pack`, `geometry.humanoid.custom`/`customSlim`).
* Хранилище `files/skins/<id>.png` + `index.json`. Установка в профиль Java — официальный
  `POST api.minecraftservices.com/minecraft/profile/skins` (multipart, ≤ 24 КБ) с токеном
  аккаунта Microsoft.

### Производительность
* Режимы Battery Saver, Balanced, Performance, Extreme Performance, Custom задают `options.txt`,
  кучу Java, масштаб рендера и параметры лаунчера. Системные настройки не меняются.
* Безопасный предел памяти: ОЗУ минус резерв для Android и драйверов (1,4–3 ГБ).
* Рендереры оцениваются по строке GPU (Adreno/Mali/PowerVR/Xclipse), версиям OpenGL ES и Vulkan и
  версии Minecraft (OpenGL 2.1 до 1.16.5, 3.2 core с 1.17).
* Тепловой контроль только читает `PowerManager.currentThermalStatus` и headroom.

### Обновления лаунчера
GitHub Releases репозитория `BuildConfig.UPDATE_REPO`. Устанавливается только APK с опубликованным
SHA-256, совпадающим с загрузкой, и с тем же сертификатом подписи, что у установленного
приложения. Автопроверка выключена по умолчанию.

## 6. Расширение
* Бэкенд исполнения Java Edition — см. выше.
* Новый загрузчик модов — `LoaderProvider` и значение в `ModLoaderType`.
* Новая строка интерфейса — строка в `i18n/strings.tsv` на четырёх языках.
* Новая причина сбоя — правило в `CrashAnalyzer` и пара строк `cc_*`/`cf_*`.
* Миграции Room — схемы в `core/data/schemas`, тест `MigrationTest`.
