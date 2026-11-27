# Anomaly

A native Android portrait arcade prototype built with Kotlin and Jetpack Compose. It includes a one-thumb classification loop, sequence/attribute/relationship patterns, rule shifts, scoring, combos, adaptive difficulty, interactive tutorial, results, local preferences, and personal-best persistence.

## Run

Open this directory in Android Studio with JDK 17+ and Android SDK platform 36 installed. Use Gradle 9.1, then run the `app` configuration on an API 26+ device/emulator. Run the unit suite with `gradle :app:testDebugUnitTest`.

## Gameplay decisions

- Relationship rules use dot counts that advance modulo four. The first item establishes the relationship baseline and cannot be anomalous.
- A classification deadline uses a monotonic clock; time spent in the background counts against the 60-second run.
- Transition alerts appear one object before the switch. The changed rule is shown with the next object before classification.
- Difficulty uses a rolling 10-decision accuracy window and six fast correct decisions to raise one dimension at a time.
- The run is offline. Personal best and sound/haptic preferences are stored locally. There are no ads or monetization SDKs.
