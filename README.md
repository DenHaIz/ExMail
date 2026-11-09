# ExMail

Android-клиент Microsoft Exchange (EWS, NTLMv2 или Basic): почта с мгновенными уведомлениями, календарь, контакты, светлая и тёмная тема.
Сервер находится по рабочему e-mail при входе; адрес можно указать и вручную.

**APK:** [Releases](https://github.com/DenHaIz/ExMail/releases) (текущая — **1.0.2**) · Android 8+ · обновляется из приложения (Настройки → «Обновления»)

## Сборка

Инструменты лежат в `.toolchain/` внутри проекта, систему не затрагивают.

```powershell
. .\env.ps1
.\gradlew assembleRelease testDebugUnitTest
```

APK: `app/build/outputs/apk/release/app-release.apk` (для отладки — `assembleDebug`).

Состояние проекта, решения, тесты и следующие шаги — в [HANDOFF.md](HANDOFF.md).
