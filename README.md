# Cicero

A personal voice assistant for Ray-Ban Meta Blayzer Optics (Gen 2) glasses, built on the
Meta Wearables Device Access Toolkit (DAT).

It is not tied to one model vendor. Nine backends ship in the catalog, the one you pick in
Settings answers your questions, and per-task routing can send different kinds of turn to
different models entirely.

Full design and phase plan: `~/.claude/plans/i-want-to-make-cheeky-papert.md`

## What this is (and isn't)

DAT cannot replace Meta AI. `"Hey Meta"` is a system-owned transaction and cannot be
intercepted, and on non-display glasses the temple captouch is reserved by the system
(tap = pause/resume, tap-and-hold = stop). The intent is to run **our own** wake phrase
alongside Meta's and answer with a model you chose.

That wake phrase is not built yet, and neither is voice capture: today you type a question
on the Ask screen and Cicero speaks the answer back through Android text-to-speech.

## What it can do

The assistant is handed up to thirteen tools per turn, and answers in words when one is
unavailable rather than failing:

| Ask for | Needs |
|---|---|
| "What am I looking at" — takes a photo through the glasses | paired, worn glasses **and** a model that can see |
| "Set an alarm for 7" / "set a timer for ten minutes" | nothing; handed to the phone's clock app |
| "Remind me in 20 minutes to X" — kept inside Cicero, not the clock app | notification permission, or it fires silently |
| "Make a note of that" / "what did I say about X" | nothing; searches past turns and saved notes |
| "Pause the music" / "skip 30 seconds" / "what's playing" | Notification Access |
| "Read my messages" / "what did Sam say" | Notification Access |
| "Where am I" | location permission (the glasses have no GPS — the phone answers) |
| "Take me to X" / "what's near me" | Google Maps, or any installed maps app |
| "What's the house at" / "set the thermostat to 70" | a Nest account, configured in Settings |

Two limits worth knowing up front. Messages are captured from notifications into memory
only — Cicero sees what arrived while it was running, keeps the last 50, and forgets them
when the process dies. And the camera tool is not offered at all to a text-only model, so
the assistant says it cannot see instead of guessing.

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

This value is a Gradle repository password and nothing more. It is read in
`settings.gradle.kts`, never becomes a `BuildConfig` field, and never reaches the running
app.

If the OAuth (`gho_`) token is ever rejected, fall back to a **classic** PAT with
`read:packages` from https://github.com/settings/tokens.

### 2. A model to talk to

Cicero ships with no working credential and cannot answer anything until you give it one.
This is all done in Settings inside the app, not at build time.

Nine providers are offered, in this order:

| Provider | Getting a key |
|---|---|
| **OpenRouter** | **sign in — nothing to paste** |
| Gemini | https://aistudio.google.com/apikey |
| Claude | https://console.anthropic.com/settings/keys |
| OpenAI | https://platform.openai.com/api-keys |
| xAI | https://console.x.ai |
| DeepSeek | https://platform.deepseek.com/api_keys |
| Groq | https://console.groq.com/keys |
| Mistral | https://console.mistral.ai/api-keys |
| Local | any OpenAI-compatible server — LM Studio, Ollama, llama.cpp, vLLM |

**OpenRouter is the path to take first.** It is the only provider you sign into rather than
paste a key for: a Custom Tab, a PKCE exchange, and Cicero holds a real, revocable
OpenRouter key that reaches every model above and several hundred more. On a fresh install
it offers *only* sign-in; the key field appears afterwards so you can see or replace what
sign-in produced.

The reason it is the headline path is worth stating plainly: a consumer Claude or ChatGPT
subscription cannot legitimately authorise a third-party app. Anthropic forbids it outright
and Google closed the same door on Gemini CLI. Pay-as-you-go API credit is the honest
option, and OpenRouter is the least painful way to buy it once.

A few things Settings does that are not obvious:

- **Model lists are discovered, not typed.** Each provider ships one seed default, but the
  picker asks the provider what it will actually accept and replaces that list as soon as
  Settings opens. An empty dropdown means a bad key or an unreachable server, not "no
  models".
- **Keys are encrypted at rest**, AES-256-GCM under a key the Android Keystore holds and
  never hands back, and they are excluded from cloud backup and device transfer. If that
  Keystore key is ever lost — a device restore, a keystore reset — the field silently reads
  back blank and you re-paste. It will not announce itself.
- **Per-task routing is off by default.** Turned on, it sends short commands to a fast model
  and open questions to a deep one, decided from the wording alone with no extra round trip.
  Opening a question with "ask Claude…" or "use Gemini…" overrides it for that turn.
- **DeepSeek, Groq and Local are declared text-only**, so selecting one silently drops the
  glasses camera tool. Local has a vision toggle for a server that can in fact see.
- **A speech-to-text section appears for every provider except Gemini**, which is the only
  one that takes raw audio. It points at a Whisper-compatible endpoint. Nothing captures
  audio yet, so it has no effect today — it is there for when voice input lands.

#### Optional: bake a Gemini key in at build time

If you want a locally built APK to work before you have typed anything, Gemini — and only
Gemini — can be seeded from the build:

```bash
echo "gemini_api_key=YOUR_KEY" >> local.properties
```

A `GEMINI_API_KEY` environment variable wins over `local.properties` when both are set.
This step is entirely optional: with neither present the value compiles to an empty string,
the build still succeeds, and the app simply starts with no Gemini key. A key entered in
Settings always beats the baked-in one.

Be aware of what it costs you. A key baked in this way sits in the APK in cleartext and
gets none of the Keystore protection above, so use it for convenience and not for a key you
care about.

### 3. Meta AI app + glasses

mwdat 0.9.0 requires **Meta AI app V282+** and **glasses firmware V126+**
(Devices tab → gear → General → About → Version).

Enable Developer Mode: Settings → App Info → tap App Version 5 times. Without it, an
unpublished app cannot register with the glasses.

> **If you already use another DAT app:** Developer Mode keeps **only one** third-party app
> registered at a time. Registering Cicero silently unregisters the previous one, and you
> re-register that app to switch back. Developer Mode can also reset itself after a Meta AI
> app or firmware update, and it is set per linked device.

Hardware is optional to build but required to be useful: without glasses, `mwdat-mockdevice`
(MockDeviceKit) covers session and camera paths — switch it on from the Glasses screen — but
the Bluetooth audio paths need the real thing.

### 4. Android permissions and toggles

Cicero asks for what it needs when it needs it, with two exceptions Android will not let an
app prompt for:

- **Notification Access** — a toggle in Android settings, not a runtime prompt. Media
  control and reading messages both depend on it, and both say so out loud when it is off.
- **Exact alarms** — never prompted for. Without the grant, reminders are scheduled
  inexactly and may fire late rather than not at all.

### 5. Optional: Nest thermostat

Only needed for the two thermostat tools. Cicero talks to the Smart Device Management REST
API rather than Google's Home APIs, which need a hub.

There is no in-app sign-in, and that is deliberate: Google's Partner Connections Manager
only redirects to a URL registered against a *web* OAuth client, and a web client needs a
secret an installed app cannot keep. So four values are pasted into Settings by hand —
Device Access project ID, OAuth client ID, OAuth client secret, and a refresh token.

Set them up through Google's Device Access console, which charges a one-off five dollars.
**Publish the OAuth consent screen rather than leaving it in testing**, or Google expires
the refresh token every seven days.

SDM rate-limits a thermostat to five calls a minute, counted per device rather than per app,
so Cicero caches the device list for 60 seconds.

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Requires JDK 17 (`JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`).

The unit tests need no device and no credential, and are the fastest proof the setup works:

```bash
./gradlew test
```

## Toolchain notes

- **Kotlin must be >= 2.2.0.** mwdat 0.9.0 ships Kotlin metadata version 2.2.0. Anything older
  (e.g. 2.0.x, which reads up to 2.1.0) fails every DAT import with "was compiled with an
  incompatible version of Kotlin". KSP is version-locked to Kotlin, so raising one means
  raising the other — see the `ksp` pin next to `kotlin` in `gradle/libs.versions.toml`.
- `android.useAndroidX=true` **and** `android.nonTransitiveRClass=true` must both be set
  explicitly; AGP 9 defaults both on, AGP 8 does not. Dropping either breaks the build the
  same way on AGP 8.
- `PhotoData` is a **sealed interface** with two variants, `PhotoData.Bitmap` and
  `PhotoData.HEIC` (a `ByteBuffer`). There is no `.data` property on the interface itself.

- AGP 8.11.1 / Gradle 8.14.1 / compileSdk 36 / JDK 17 — the combination verified to work
  with mwdat 0.9.0. Android Studio's newer AGP 9.x default is untested against this SDK.
- `minSdk 31` is required by `AudioManager.setCommunicationDevice()`, used for the
  Bluetooth HFP microphone path.
- The project deliberately lives outside OneDrive; OneDrive sync corrupts Gradle lock files
  in `build/` and `.gradle/`.
- Adding a provider means adding a line to `Providers.all` and nothing else. If a
  `when (provider)` ever appears anywhere, that design has been broken.
- The tool list order in `ToolRegistry` is a cache key — it is the head of a cached prompt
  prefix, so new tools go on the end and nothing is reordered.

## Debugging

```bash
adb logcat -s Cicero:V
```
