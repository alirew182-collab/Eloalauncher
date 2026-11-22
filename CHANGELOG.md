# Changelog

## 1.1.0

### Added
- Navigation: bottom bar with Home, Library, Mods, Worlds, Accounts and Settings on phones, a rail on medium screens and a permanent drawer on tablets. Top bar with notifications and account.
- Home: Java Edition and Bedrock Edition cards, each with version, install status, Play, Settings and Versions. Launch-mode picker on the Java card. Three layouts: cards, dashboard, minimal.
- Accounts: Microsoft sign-in in the system browser (authorization code with PKCE) next to the device-code flow. Launch modes Official account, Online, Offline and Local singleplayer with the reason shown when a mode is unavailable. Offline mode uses an account already verified as a game owner and makes no network calls.
- Worlds: Bedrock world backups as `.mcworld` from a folder granted through the Storage Access Framework, import, CRC and SHA-256 verification, restore with automatic backup of the world being replaced, export, rename, delete, search and sort. Java worlds per profile.
- Performance: device facts (SoC, CPU, GPU, OpenGL ES, Vulkan, display, RAM, storage, background restrictions), five modes (Battery Saver, Balanced, Performance, Extreme Performance, Custom) that write the profile's `options.txt`, heap and render scale, a memory manager with the safe limit, a renderer list (GL4ES, GL4ES + ANGLE, MobileGlues, Zink) evaluated for the GPU and Minecraft version, and a thermal monitor that warns when Android reports a high thermal status.
- Game settings: render and simulation distance, graphics, VSync, FPS limit, mipmaps, particles, clouds, shadows, smooth lighting, fullscreen, HUD scale, FOV, audio, sensitivity, memory, garbage collector, JVM and game arguments, window size and Java runtime per profile. Values are merged into `options.txt`, keeping unknown keys.
- Controls: touch layout editor with drag, resize, opacity, labels and actions; presets Classic, Compact, PVP, Survival, Controller-like and Custom; JSON import and export; gamepad detection, input tester and per-controller mapping with dead zone and sensitivity.
- Java runtimes screen: architecture, required Java per Minecraft version, import, per-file SHA-256 integrity check, size, delete. Automatic selection prefers the exact Java major version.
- Downloads screen: install queue with progress, speed and remaining time, pause, resume, cancel and retry.
- Notification centre with system notifications for finished downloads, install errors, low storage, updates, damaged files and overheating.
- Crash analysis in the log screen for known causes (out of memory, wrong Java, missing native library, OpenGL, mod dependencies, mixin errors, corrupted files and others), also for a game log picked from storage.
- Update manager for the launcher's GitHub releases: SHA-256 is required, the signing certificate must match the installed app, installation goes through the system installer. Daily check is off by default.
- Privacy screen listing stored data and contacted hosts; button to delete all launcher data.
- Interface in English, Russian, Turkish and German, generated from `i18n/strings.tsv`, with an in-app language picker. Interface density and element size settings.
- GitHub Actions workflow: translation check, tests, lint, debug and release APKs with checksums.

### Changed
- Visual pass: flat surfaces with hairline borders instead of colour gradients, one accent colour, type weights 400 to 600, sentence case, a square status marker.
- Database schema version 2 (migration from version 1 included).

### Fixed
- `SafeZip.verifyIntegrity()` now compares CRC-32 values. `java.util.zip.ZipFile` does not, so a damaged byte inside a stored entry was not detected before.
- Update repository slugs such as `../x` are rejected.

## 1.0.0
- Bedrock APK library with signature and integrity checks and installation through the system installer; add-on import and analysis.
- Java Edition: official version metadata, verified downloads, profiles, Fabric, Quilt, Forge and NeoForge, mod manager, resource packs, shaders, datapacks, modpacks, file manager, diagnostics, Microsoft device-code sign-in.
