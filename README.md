# Anomaly

A native Android portrait arcade prototype built with Kotlin and Jetpack Compose. It combines a one-thumb anomaly-detection loop with ECHO, a remote handler who briefs the operator and sends occasional event-driven transmissions during a run.

## Included

- Operation 001 briefing before the first run, replayable from Settings.
- Curated ECHO dialogue for streaks, repeated mistakes, subtle catches, protocol shifts, low integrity, and final seconds.
- Dialogue cooldowns, priorities, non-overlap, automatic dismissal, and alternative lines.
- Operation metadata and briefing/dialogue content foundations for Operations 001–004.
- Persisted ECHO, subtitle, music, and sound-effect preferences.
- A small audio-controller interface; current classification tones remain native effects. No music or voice assets are included.
- Existing pattern families, deterministic classification, transitions, scoring, combos, adaptive difficulty, tutorial, and results.

## Run

Open this directory in Android Studio with JDK 17+ and Android SDK platform 36 installed. Use Gradle 9.1, then run the `app` configuration on an API 26+ device/emulator. Run unit tests with `gradle :app:testDebugUnitTest`.

## Gameplay decisions

- Relationship rules use dot counts that advance modulo four. The first item establishes the relationship baseline and cannot be anomalous.
- A classification deadline uses a monotonic clock; time spent in the background counts against the 60-second run.
- Transition alerts and the active rule remain visible independently of ECHO dialogue.
- ECHO is event-driven, not per-answer commentary. Routine messages have a 10-second cooldown; important transmissions can bypass it but never interrupt another important message.
- Dialogue is a non-interactive UI layer. It does not pause the run, alter deadlines, or intercept decision taps.
- The game is offline and has no ads, monetization SDKs, backend, voice playback, or bundled music.
