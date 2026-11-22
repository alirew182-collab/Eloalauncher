# Eloa Launcher

Лаунчер Minecraft для Android. В одном приложении: Bedrock Edition (запуск установленной игры, свои APK, дополнения, резервные копии миров) и Java Edition (версии Mojang, профили, Fabric/Quilt/Forge/NeoForge, моды, ресурспаки, шейдеры, настройки игры, Microsoft- и Ely.by-вход, скины).

Kotlin, Jetpack Compose, Material 3, Coroutines, Room, DataStore, Hilt, WorkManager, SAF. minSdk 26 (Android 8.0), target/compileSdk 36. Интерфейс на английском, русском, турецком и немецком.

* Архитектура и решения: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
* Как пользоваться: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)
* Что проверено и чем: [docs/TESTING.md](docs/TESTING.md)
* Изменения: [CHANGELOG.md](CHANGELOG.md), зависимости и лицензии: [LICENSES.md](LICENSES.md)

## Что работает, а что нет

| Возможность | Статус |
|---|---|
| Bedrock: обнаружение установленной игры, версия, запуск, инструкция по официальной установке | работает |
| Bedrock: импорт своих APK/APKS/XAPK с проверкой подписи, ABI, minSdk; установка через системный установщик | работает |
| Bedrock: .mcpack, .mcaddon, .mcworld, .mctemplate, .mcstructure, .zip — проверка manifest.json, UUID, зависимостей; передача в игру через Intent | работает |
| Bedrock: резервные копии миров из папки, выданной через SAF, импорт .mcworld, проверка CRC и SHA-256, восстановление, экспорт | работает; миры во внутренней памяти Minecraft недоступны (ограничение Android) |
| Java: версии Mojang с проверкой SHA-1, профили, загрузчики Fabric/Quilt полностью, Forge/NeoForge без шагов processors | работает |
| Java: моды, ресурспаки, шейдеры, датапаки, миры, сборки .mrpack/.eloapack | работает |
| Java: настройки игры записываются в `options.txt` профиля | работает |
| Microsoft-вход: браузер (код + PKCE) и код устройства, режимы Online/Offline/Official/Local singleplayer | работает; нужен ваш Azure Client ID, одобренный Mojang |
| Скины: импорт PNG, скин из фото, редактор (части тела, слои, заливка, пипетка, отмена), экспорт PNG, набор скинов для Bedrock, установка в профиль Microsoft | работает; установка в профиль и Bedrock проверены только на имитации сервера и intent |
| Ely.by: вход (с 2FA), authlib-injector при запуске Java, только при наличии проверенного аккаунта Microsoft | работает на имитации сервера; на реальном ely.by не проверялось (сеть контейнера закрыта) |
| Производительность: определение устройства, 5 режимов, память, оценка рендереров, тепловой статус | работает |
| Управление: редактор сенсорных раскладок, профили геймпадов, тест ввода | работает; раскладки применяются игровым бэкендом, которого в сборке нет |
| Обновления лаунчера из GitHub Releases с проверкой SHA-256 и подписи | работает |
| **Непосредственный запуск Java Edition** | **нет**: нужен бэкенд (JVM для Android + LWJGL + слой трансляции OpenGL). Лаунчер готовит всё и показывает точную причину, без имитации запуска |

## Сборка

```bash
# JDK 17+ (проверено на 21), Android SDK: platform 36, build-tools 36.0.0
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                          # модульные, интеграционные и UI-тесты (JVM + Robolectric)
./gradlew :app:lintDebug                # Android Lint
python3 scripts/i18n/gen_strings.py     # пересобрать строки из i18n/strings.tsv
python3 scripts/test_summary.py         # сводка результатов тестов
```

### Тексты и переводы

Все тексты интерфейса лежат в `i18n/strings.tsv` (ключ, en, ru, tr, de). `scripts/i18n/gen_strings.py` создаёт из таблицы `res/values*/strings.xml`; файлы XML не редактируются вручную. `--check` падает, если перевода нет или XML устарели (так делает CI).

### Release

Ключ подписи создаёт и хранит владелец проекта, в репозиторий он не попадает:

```bash
keytool -genkeypair -keystore ~/eloa-release.jks -alias eloa -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # пути и пароли
./gradlew :app:assembleRelease                        # R8, shrinkResources, подпись v2
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Без `keystore.properties` release-сборка остаётся неподписанной. В GitHub Actions подпись берётся из секретов `ELOA_KEYSTORE_BASE64`, `ELOA_STORE_PASSWORD`, `ELOA_KEY_ALIAS`, `ELOA_KEY_PASSWORD`, если они заданы.

## Модули

```
:core:common       архивы (Zip Slip, лимиты, CRC), хеши, версии, TOML, логирование
:core:designsystem тема, компоненты
:core:data         Room (схема 2 + миграция), DataStore, Keystore-шифрование, устройство, режимы производительности
:domain:bedrock    APK, подписи, дополнения, NBT, архивы миров
:domain:java       метаданные, загрузки, установщики, загрузчики модов, план запуска, options.txt,
                   режимы запуска, PKCE, рендереры, раскладки управления, анализ сбоев, обновления
:app               DI, навигация, экраны, WorkManager, PackageInstaller, SAF
```

Minecraft — товарный знак Mojang Synergies AB. Проект не связан с Mojang или Microsoft.
