# Dependencies and licenses

All libraries are fetched from Google Maven or Maven Central at build time. No Minecraft files, APKs or assets are part of the repository or the APK.

| Component | Version | License |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | Apache 2.0 |
| Kotlin, Compose compiler plugin | 2.2.21 | Apache 2.0 |
| KSP | 2.3.4 | Apache 2.0 |
| Jetpack Compose BOM (UI, Material 3, icons) | 2025.10.01 | Apache 2.0 |
| AndroidX Core, Activity, Lifecycle, Navigation | 1.17.0 / 1.11.0 / 2.9.4 / 2.9.5 | Apache 2.0 |
| Hilt / Dagger | 2.57.2 | Apache 2.0 |
| AndroidX Hilt | 1.3.0 | Apache 2.0 |
| Room | 2.8.3 | Apache 2.0 |
| DataStore | 1.1.7 | Apache 2.0 |
| WorkManager | 2.10.5 | Apache 2.0 |
| kotlinx.coroutines | 1.10.2 | Apache 2.0 |
| kotlinx.serialization | 1.9.0 | Apache 2.0 |
| OkHttp, MockWebServer (tests) | 4.12.0 | Apache 2.0 |
| apksig | 8.13.2 | Apache 2.0 |
| JUnit 4 (tests) | 4.13.2 | EPL 1.0 |
| MockK (tests) | 1.14.6 | Apache 2.0 |
| Robolectric (tests) | 4.16 | MIT |
| Turbine (tests) | 1.2.1 | Apache 2.0 |

## Referenced, not bundled

These renderer layers are listed and evaluated on the Performance screen. Their libraries are not included in this build; a runtime backend that ships them must follow their licenses.

| Component | License | Source |
|---|---|---|
| GL4ES | MIT | https://github.com/ptitSeb/gl4es |
| ANGLE | BSD-3-Clause | https://chromium.googlesource.com/angle/angle |
| MobileGlues | LGPL-2.1 | https://github.com/MobileGL-Dev/MobileGlues |
| Zink (Mesa) | MIT | https://docs.mesa3d.org/drivers/zink.html |
| Amethyst (PojavLauncher fork), studied for the backend design | LGPL-3.0 | https://github.com/AngelAuraMC/Amethyst-Android |

## Design and writing references

The interface and text were reviewed against two public rule sets:
- unslop by Damir Sokolovsky, CC BY 4.0, https://github.com/heydamir/unslop
- no-ai-slop by Peter Yang, https://github.com/petergyang/no-ai-slop

Minecraft is a trademark of Mojang Synergies AB. This project is not affiliated with Mojang or Microsoft.
