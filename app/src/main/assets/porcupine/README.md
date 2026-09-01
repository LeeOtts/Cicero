# Wake word keyword

Cicero ships **no** keyword file. Train your own and drop it in beside this
note as `hey_cicero_android.ppn`, or import it from Settings, which copies it
into the app's own storage without needing a rebuild.

1. Sign in at https://console.picovoice.ai (free for personal use).
2. Train the phrase "Hey Cicero" and choose platform **Android**. A `.ppn`
   trained for any other platform loads and then simply never fires.
3. Copy the access key from the console into Settings.

Two things to know before this bites you:

- **The keyword and the engine version move together.** A file trained against
  a different Porcupine major version fails at runtime, not at build time, so a
  version bump in `libs.versions.toml` means re-training. The pin there carries
  the same warning.
- **Free-tier keywords can expire.** When one does, the failure is a wake word
  that has quietly stopped working. Settings reports the engine's own message
  and lets you import a fresh file, which is why the import exists at all.

Nothing here is committed: the keyword is issued under your console account,
not this repository's.
