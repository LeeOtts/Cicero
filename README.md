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

`com.meta.wearable:mwdat-*` is published to GitHub Packages, not Maven Central or Google's
Maven (both return 404). GitHub Packages requires authentication **even though the SDK repo
is public**, so an unauthenticated build fails with 401 Unauthorized.

The SDK cannot be vendored into this repo to avoid that: the Meta Wearables Developer Terms
are non-transferrable and non-sublicensable, and each developer must accept them
individually. Fetching the SDK with your own credential *is* how you accept them.

One command, and nothing is written to disk — the token stays in your OS keyring:

```bash
gh auth refresh -h github.com -s read:packages
```

`settings.gradle.kts` resolves a credential in this order, taking the first that is set:

| # | Source | Use it when |
|---|---|---|
| 1 | `GITHUB_TOKEN` environment variable | CI, or a shell you already export in |
| 2 | `github_token` in `local.properties` | you prefer a classic PAT |
| 3 | `gh auth token` (OS keyring) | everyday local development |

Option 3 is preferred because it is the only one that never puts a live token in a file.
If you use option 2, note that `local.properties` is gitignored and must stay that way —
and be aware that anything which reads that file (including AI coding tools) can capture
the token.

If the OAuth (`gho_`) token is ever rejected, fall back to a **classic** PAT with
`read:packages` from https://github.com/settings/tokens.

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

> **If you already use another DAT app:** Developer Mode keeps **only one** third-party app
> registered at a time. Registering Cicero silently unregisters the previous one, and you
> re-register that app to switch back. Developer Mode can also reset itself after a Meta AI
> app or firmware update, and it is set per linked device.

### 4. Your own model API key

Cicero talks to Gemini, Claude or an OpenAI-compatible endpoint, and ships with no
credentials. Set one up in Settings inside the app, or see step 2 above.

Hardware is optional to build but required to be useful: without glasses, `mwdat-mockdevice`
(MockDeviceKit) covers session and camera paths, but the Bluetooth audio paths need the real
thing.

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
