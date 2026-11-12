# Voice Typer Mobile — Android dictation bubble

## What this project is
The Android counterpart of Voice Typer (Windows, repo `hugocodings-design/Voice-Typer`): a floating mic
bubble over every app; tap → dictate → text is formatted by the app's preset and written into the focused
field. **Local only**: on-device recognition; INTERNET is used only to download Whisper models from Hugging
Face (audio/text never leave the phone). UI texts are in French.
Android only: iOS forbids overlays and writing into other apps (a custom keyboard would be the iOS route).

## Layout
- `core/` — pure Kotlin/JVM, unit-tested (`./gradlew -PcoreOnly :core:test`, works without Android SDK)
  - `VoiceCommands.kt` — port of desktop `commands.py` (FR/EN commands, `isUndo` « efface ça » said alone)
  - `Dictionary.kt` — replacements (+ per-app), `Text.kt` — capitalisation, fillers, sentences
  - `Preset.kt` (`Style`, `Preset`, `AppRule`, `Defaults`), `Styles.kt` — rule-based styles (EMAIL greeting/
    closing/signature, MESSAGE, NOTES, CLEAN, RAW). No LLM yet (roadmap: on-device LLM like desktop Qwen)
  - `Pipeline.kt` — raw → commands → dictionary → fillers → style → prefix/suffix (+ trailing space)
  - `Insertion.kt` — merge into the field at the cursor (spacing, mid-sentence lowercase, selection)
  - `Settings.kt` — kotlinx.serialization JSON, `sanitized()` keeps presets/default/app rules consistent
- `app/` — Android (minSdk 26, target 35, Compose)
  - `VoiceAccessibilityService` — tracks the focused editable node (`findFocus(FOCUS_INPUT)`, IME window),
    shows/hides the bubble (700 ms debounce), FR/EN badge, menu language switch, runs dictation, inserts. Long press: menu; during dictation: cancel
  - `OverlayController` / `BubbleView` — TYPE_ACCESSIBILITY_OVERLAY windows, FLAG_NOT_FOCUSABLE (the target
    keeps focus): bubble (drag/tap/long-press), caption (live transcript), preset menu
  - `SpeechEngine` — SpeechRecognizer, on-device when available; tap-to-stop = segmented session (API 33+), else restart after each pause and
    concatenate segments. Main thread only
  - `TextInserter` — ACTION_SET_TEXT + ACTION_SET_SELECTION, fallback clipboard (EXTRA_IS_SENSITIVE) + ACTION_PASTE; undo of the last insert
  - Whisper path (`Settings.engine == WHISPER` + downloaded model, else falls back to Android recognition):
    `app/src/main/cpp` (whisper.cpp v1.9.5 via CMake FetchContent, static, arm64-v8a only, `whisper_jni.cpp`),
    `WhisperNative` (JNI), `WhisperEngine` (one model kept loaded, preloaded by the service), `AudioCapture`
    (16 kHz AudioRecord, levels, silence auto-stop), `ModelManager` (download to filesDir/models, `.part` then rename).
    Silence (max level < 1) is never sent to Whisper; `Text.stripWhisperHallucinations` cleans the output;
    `Dictionary.whisperPrompt` = replacement targets as initial prompt. Catalog in core `Models.kt`
  - `MicService` — foreground service type microphone while dictating (best effort); the recognizer starts 400 ms later
  - `SettingsRepository` — StateFlow shared by service and activity (same process), SharedPreferences JSON
  - `DiagnosticLog` — in-memory event log (never dictated text) shown/copied in the Diagnostic card (raw AudioRecord peak test + recognizer test)
  - `MainActivity` — single Compose screen: setup steps, try field, diagnostic, language, presets, per-app, dictionary, signature, bubble, commands

## Build
- No Android SDK in cloud sessions (dl.google.com blocked): run core tests with `-PcoreOnly`; the APK is
  built by `.github/workflows/build.yml` (artifact + a GitHub release per new `versionName`, created by CI —
  cloud sessions may not push tags or create releases themselves). Release = bump `versionCode`/`versionName`.
- Cloud sessions may not compile whisper.cpp (external code); the native part is only built by CI.
- Plugin versions live in `settings.gradle.kts` (`pluginManagement.plugins`), so `-PcoreOnly` never resolves AGP.
