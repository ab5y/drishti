# Drishti Eye Chart

An offline Android app for vision pre-screening.

It is a screening aid, not a medical diagnosis.

## Download

Latest APK: https://github.com/ab5y/drishti/releases/latest/download/drishti.apk

Each release also has a `drishti.apk.sha256` file. Download it next to the APK and check the APK against it:

```sh
# Linux
sha256sum -c drishti.apk.sha256
# macOS
shasum -a 256 -c drishti.apk.sha256
```

```powershell
# Windows PowerShell: prints True if the APK matches
(Get-FileHash drishti.apk -Algorithm SHA256).Hash -eq (Get-Content drishti.apk.sha256).Split(' ')[0]
```

To install, allow "Install unknown apps" for the browser or file manager you open the APK from. Android may show a Play Protect warning, because the app is not from the Play Store.

Requires Android 7.0 (API 24) or newer.

## Build from source

JDK 17 or newer and the Android SDK (platform 37).

```sh
./gradlew assembleDebug
```

Release builds are signed by the GitHub Actions workflow when a version tag is pushed.
