# Eloa Launcher

Лаунчер Minecraft для Android, объединяющий **Bedrock Edition** (библиотека собственных APK,
дополнения, миры) и **Java Edition** (версии Mojang, профили, Fabric/Quilt/Forge/NeoForge, моды,
ресурспаки, шейдеры, датапаки, сборки), с файловым менеджером, диагностикой, журналом и
Microsoft-авторизацией.

* Kotlin · Jetpack Compose · Material 3 · Coroutines/Flow · Room · DataStore · Hilt · WorkManager · SAF
* minSdk 26 (Android 8.0) · target/compileSdk 36
* Архитектура и обоснование версий: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
* Инструкция пользователя: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)
* Отчёт о тестировании и известные ограничения: [docs/TESTING.md](docs/TESTING.md)

## Важно: что работает и что нет

| Возможность | Статус |
|---|---|
| Bedrock: импорт APK/APKS/XAPK, проверка подписи/целостности/ABI/minSdk, библиотека, установка через системный Package Installer, запуск | реализовано |
| Bedrock: .mcpack/.mcaddon/.mcworld/.mctemplate/.mcstructure/.zip — анализ, зависимости, UUID, конфликты, передача в игру | реализовано |
| Java: версии Mojang, загрузка/проверка всех файлов, профили, загрузчики Fabric/Quilt (полностью), Forge/NeoForge (кроме шагов processors) | реализовано |
| Java: менеджер модов, ресурспаки, шейдеры, датапаки, миры, конфиги, сборки .mrpack/.eloapack | реализовано |
| Java: **непосредственный запуск игры** | **нет**: требуется нативный backend (JVM для Android + LWJGL + GL-транслятор). Лаунчер готовит всё и показывает точную причину блокировки — без имитации запуска |
| Microsoft-вход | реализован, но нужен ваш Azure Client ID, одобренный Mojang для Minecraft API |

## Сборка

```bash
# Требуется JDK 17+ (проверено на 21) и Android SDK (platform 36, build-tools 36.0.0)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                     # все unit/интеграционные/UI-тесты (JVM + Robolectric)
./gradlew :app:lintDebug           # Android Lint
python3 scripts/test_summary.py    # сводка результатов тестов
```

### Release

Создайте собственный ключ (храните его и пароль вне репозитория — без него нельзя выпускать обновления):

```bash
keytool -genkeypair -keystore ~/eloa-release.jks -alias eloa -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # заполните пути и пароли
./gradlew :app:assembleRelease                        # R8 + shrinkResources, подпись APK v2
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Без `keystore.properties` release-сборка формируется неподписанной.

## Модули

```
:core:common       безопасные архивы (Zip Slip, лимиты), хеши, версии/диапазоны, TOML, логирование
:core:designsystem тема (тёмная/светлая/акценты), компоненты, skeleton
:core:data         Room, DataStore, логгер, определение железа, шифрование токенов, хранилище
:domain:bedrock    AXML-парсер, анализ APK + apksig, add-ons, NBT
:domain:java       метаданные Mojang, загрузчик, установщики, загрузчики модов, моды, план запуска, авторизация
:app               DI, навигация, экраны, WorkManager, PackageInstaller, SAF
```

Тестовые APK-фикстуры воспроизводимо генерируются `scripts/make_apk_fixtures.sh`.

Minecraft — товарный знак Mojang Synergies AB. Проект не связан с Mojang или Microsoft.
