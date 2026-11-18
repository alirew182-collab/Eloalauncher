# EloaLauncher — технический план и архитектура

## 1. Результаты анализа окружения (этап 1)

| Компонент | Состояние |
|---|---|
| JDK | OpenJDK 21 |
| Gradle | 8.14.3 (wrapper) |
| Android SDK | установлен вручную: platform 36, build-tools 36.0.0, platform-tools |
| Эмулятор / KVM | **нет** (`/dev/kvm` отсутствует) → инструментальные и UI-тесты выполняются на JVM через Robolectric |
| Сеть | Google Maven, Maven Central, Gradle Portal — доступны. Mojang/Fabric/Forge meta API — **заблокированы** политикой контейнера сборки (на устройстве доступны). Сетевая логика тестируется на MockWebServer. |

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
:core:common      (Kotlin/JVM) — безопасная работа с архивами (Zip Slip, лимиты), хеши,
                    семантические версии и диапазоны (SemVer/Fabric, Maven/Forge), TOML,
                    API логирования, Result-типы. Без зависимостей от Android → быстрые тесты.
:domain:bedrock   (Kotlin/JVM) — парсер бинарного AndroidManifest (AXML), анализ APK/APKS,
                    проверка подписи (apksig), анализ Bedrock add-on (manifest.json, UUID,
                    зависимости, конфликты), миров.
:domain:java      (Kotlin/JVM) — модели метаданных Mojang (version_manifest_v2, version.json),
                    правила библиотек, построение плана запуска (classpath, аргументы),
                    менеджер загрузок (очередь, параллелизм, докачка, SHA-1, ретраи),
                    установщики загрузчиков (Fabric/Quilt/Forge/NeoForge), парсеры метаданных
                    модов (fabric.mod.json, quilt.mod.json, mods.toml, neoforge.mods.toml,
                    mcmod.info), проверка зависимостей и конфликтов модов.
:core:designsystem (Android) — тема Material 3 (тёмная/светлая/акценты), компоненты, skeleton.
:core:data        (Android) — Room, DataStore, логирование в БД с ротацией, определение
                    железа (SoC/CPU/GPU/RAM/Vulkan/GLES/термо), хранилище, шифрование токенов
                    через Android Keystore.
:app              (Android) — Hilt DI, навигация, экраны и ViewModel'и, WorkManager,
                    PackageInstaller, SAF, Microsoft OAuth (device code flow).
```

Зависимости направлены строго вниз: `app → data/designsystem → domain → common`.

## 4. Ключевые решения и ограничения (честно)

* **Bedrock**: установка только через системный `PackageInstaller` (сессии, подтверждение
  пользователем). Если установленная копия подписана другим сертификатом или новее
  импортируемой — установка не начинается, пользователь получает объяснение (Android не
  допускает замену подписи и даунгрейд без удаления). Add-ons передаются в игру через
  официальный `ACTION_VIEW` + `FileProvider` (Minecraft импортирует их сам) либо копируются
  в папку `com.mojang`, доступ к которой пользователь выдал через SAF.
* **Java Edition**: лаунчер полностью готовит экземпляр — метаданные Mojang, клиент,
  библиотеки, нативы, ассеты с проверкой SHA-1, профили загрузчиков, план запуска
  (classpath, JVM- и игровые аргументы). Непосредственное исполнение игры на Android требует
  нативного порта JVM + LWJGL + GL-транслятора (уровень PojavLauncher), который не может быть
  собран и проверен в этом окружении. Поэтому исполнение вынесено за интерфейс
  `GameRuntimeBackend`; пока backend не установлен, кнопка запуска выполняет полную
  предстартовую проверку и показывает точную причину невозможности запуска (без имитации).
* **Forge/NeoForge**: установщик скачивается с официального maven, извлекаются
  `install_profile.json`/`version.json`, библиотеки скачиваются; шаги `processors`
  требуют исполнения Java и выполняются runtime-backend'ом.
* **Microsoft-авторизация**: OAuth 2.0 Device Code Flow → Xbox Live → XSTS → Minecraft
  Services, проверка владения игрой (entitlements). Требуется Azure Client ID,
  одобренный для Minecraft API: задаётся в настройках. Встроенного «чужого» ID нет.
  Токены шифруются AES-GCM ключом из Android Keystore. Офлайн-аккаунтов нет.

## 5. Расширение

* Новый backend исполнения Java Edition подключается реализацией `GameRuntimeBackend` и привязкой в
  `di/BackendModule.kt` — план запуска, предстартовая проверка и UI уже готовы.
* Новый загрузчик модов — реализация `LoaderProvider` + значение в `ModLoaderType`.
* Новый тип контента — значение `JavaContentType` и правило в `ContentValidator`.
* Новые проверки диагностики — блок `check(...)` в `DiagnosticsEngine` и, при необходимости, `AutoFix`.
* Миграции Room: схемы экспортируются в `core/data/schemas`.
