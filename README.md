# Cicero

A personal Gemini assistant for Ray-Ban Meta Blayzer Optics (Gen 2) glasses, built on the
Meta Wearables Device Access Toolkit (DAT).

Full design and phase plan: `~/.claude/plans/i-want-to-make-cheeky-papert.md`

## What this is (and isn't)

DAT cannot replace Meta AI. `"Hey Meta"` is a system-owned transaction and cannot be
intercepted, and on non-display glasses the temple captouch is reserved by the system
(tap = pause/resume, tap-and-hold = stop). This app runs its **own** wake phrase alongside
Meta's, and speaks to Gemini instead.

## One-time setup

### 1. GitHub Packages token

`com.meta.wearable:mwdat-*` is published to GitHub Packages, not Maven Central. Without a
token with `read:packages`, Gradle fails with 401 Unauthorized.

```bash
gh auth refresh -h github.com -s read:packages
echo "github_token=$(gh auth token)" >> local.properties
```

If the OAuth (`gho_`) token is rejected, use a **classic** PAT with `read:packages` from
https://github.com/settings/tokens instead.

### 2. Gemini API key

Create one at https://aistudio.google.com/apikey, then:

```bash
echo "gemini_api_key=YOUR_KEY" >> local.properties
```

Both values are read in `app/build.gradle.kts` and reach the app via `BuildConfig`.
`local.properties` is gitignored — keep it that way.

### 3. Meta AI app + glasses

mwdat 0.9.0 requires **Meta AI app V282+** and **glasses firmware V126+**
(Devices tab → gear → General → About → Version).

Enable Developer Mode: Settings → App Info → tap App Version 5 times. Without it, an
unpublished app cannot register with the glasses.

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Requires JDK 17 (`JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`).

## Toolchain notes

- **Kotlin must be >= 2.2.0.** mwdat 0.9.0 ships Kotlin metadata version 2.2.0. Anything older
  (e.g. 2.0.x, which reads up to 2.1.0) fails every DAT import with "was compiled with an
  incompatible version of Kotlin".
- `android.useAndroidX=true` must be set explicitly; AGP 9 defaults it on, AGP 8 does not.
- `PhotoData` is a **sealed interface** with two variants, `PhotoData.Bitmap` and
  `PhotoData.HEIC` (a `ByteBuffer`). There is no `.data` property on the interface itself.

- AGP 8.11.1 / Gradle 8.14.1 / compileSdk 36 / JDK 17 — the combination verified to work
  with mwdat 0.9.0. Android Studio's newer AGP 9.x default is untested against this SDK.
- `minSdk 31` is required by `AudioManager.setCommunicationDevice()`, used for the
  Bluetooth HFP microphone path.
- The project deliberately lives outside OneDrive; OneDrive sync corrupts Gradle lock files
  in `build/` and `.gradle/`.

## Debugging

```bash
adb logcat -s Cicero:V
```

Log every audio-route transition — that is where the bugs will be.
