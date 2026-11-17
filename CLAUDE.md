# Voice Typer Mobile — Android dictation bubble

## What this project is
The Android counterpart of Voice Typer (Windows, repo `hugocodings-design/Voice-Typer`): a floating mic
bubble over every app; tap → dictate → text is formatted by the app's preset and written into the focused
field. **Local only**: on-device recognition; INTERNET is used only to download Whisper models from Hugging
Face (audio/text never leave the phone). UI texts are in French.
Exception, opt-in: engine « PC (boosté) » sends the audio to the Whisper server on the user's own PC, through Tailscale only.
Android only: iOS forbids overlays and writing into other apps (a custom keyboard would be the iOS route).

## Layout
- `core/` — pure Kotlin/JVM, unit-tested (`./gradlew -PcoreOnly :core:test`, works without Android SDK)
  - `VoiceCommands.kt` — port of desktop `commands.py` (FR/EN commands, `isUndo` « efface ça » said alone)
  - `Dictionary.kt` — replacements (+ per-app); `Settings.vocabulary` = words without « entendu »: `applyVocabulary`
    rewrites close spellings (case/accents/spaces/hyphens ignored; parts ≥ 5 letters may be split anywhere) as typed,
    then `soundsLike` (phonetic key C/K/QU, PH/F… + edit distance 0/1/2 by key length, same first sound, ±1 word),
    and they lead the Whisper prompt. `Text.kt` — capitalisation, fillers, sentences
  - `Preset.kt` (`Style`, `Preset`, `AppRule`, `Defaults`), `Styles.kt` — rule-based styles (EMAIL greeting/
    closing/signature — default « Merci, » only if neither the dictation nor the signature has a closing — MESSAGE, NOTES, CLEAN, RAW).
    Courriel language = `guessLang` of the text, else of the client email, else the dictation language; it picks greeting,
    closing and `Settings.signatureFor` (`signatureEn` for English if set, else `signature`). No LLM yet (roadmap: on-device LLM like desktop Qwen)
  - `Pipeline.kt` — raw → commands → dictionary → fillers → style → prefix/suffix (+ trailing space)
  - `EmailSubject.kt` — Courriel presets (`Preset.emailSubject`, on by default): rule-based subject (skips greeting,
    small talk, « je vous écris pour… », ≤ 8 words) → `Pipeline.Result.subject`/`subjectLine`. The service fills the app's
    empty subject field (view id/hint « subject »/« objet »), else puts « Objet : … » first in an empty body; dictating in
    the subject field writes only the subject
  - `ClientContext.kt` — pasted client email (`Settings.clientContext`, card « Réponse à un client »): rule-based sender
    name (De :/From:, else line after the closing, else address), address, « RE : » subject and proper nouns (capitalised
    mid-sentence, acronyms, signature lines; minus `clientIgnoredTerms`). While set, Courriel dictations everywhere use the
    terms as vocabulary (+ Whisper prompt, `Settings.vocabularyFor`), « Bonjour <prénom>, » and the RE subject.
    The card has preset chips (`Settings.replyPresetId`, default first Courriel) and « Dicter la réponse », which focuses its
    reply field and calls `VoiceAccessibilityService.instance.dictateFromApp(preset)`: the client context then applies to
    any preset (`Pipeline.process(useClient)`); « Corriger avec les termes » reapplies them to the reply text. Both fields have Coller/Copier/Couper (`EditButtons`: selection, else whole text); preset chips sit at the bottom of the card, under the dictation and Courriel buttons. Presets can be deleted (trash icon, never the last one)
  - `Insertion.kt` — merge into the field at the cursor (spacing, mid-sentence lowercase, selection)
  - `Settings.kt` — kotlinx.serialization JSON, `sanitized()` keeps presets/default/app rules consistent
- `app/` — Android (minSdk 26, target 35, Compose)
  - `VoiceAccessibilityService` — tracks the focused editable node (`findFocus(FOCUS_INPUT)`, IME window),
    shows/hides the bubble (700 ms debounce), FR/EN badge, menu language switch, runs dictation, inserts. Long press: menu; during dictation: cancel.
    A style picked in the menu is remembered for that app (app rule) unless « Juste cette fois » is checked; a plain tap uses it
  - `OverlayController` / `BubbleView` — TYPE_ACCESSIBILITY_OVERLAY windows, FLAG_NOT_FOCUSABLE (the target
    keeps focus): bubble (drag/tap/long-press), caption (live transcript), preset menu
  - `SpeechEngine` — SpeechRecognizer, on-device when available; tap-to-stop = segmented session (API 33+), else restart after each pause and
    concatenate segments. Main thread only. Offline language pack missing (error 12/13, or network errors when
    offline-only) → `onLanguageMissing`: the service continues the same dictation with a downloaded Whisper model
    (chosen one, else small > base > tiny > turbo); otherwise the error tells the user to download the language
  - Whisper transcription shows an elapsed-seconds caption; a slow run (> 10 s and > 3× the audio) suggests a lighter model
  - `TextInserter` — ACTION_SET_TEXT + ACTION_SET_SELECTION, fallback clipboard (EXTRA_IS_SENSITIVE) + ACTION_PASTE; undo of the last insert
  - `Settings.output` (Bulle card): FIELD, FIELD_AND_CLIPBOARD or CLIPBOARD — a plain, non-sensitive clip so « Lien avec
    Windows » syncs it to the PC. No field (bubble « Toujours ») → the dictation still runs and goes to the clipboard.
    The reply card's dictation always lands in its field. Silence stop (tap-to-stop off, Whisper/PC capture):
    core `SilenceDetector`, adaptive noise floor that rises only on quiet frames (speech starts 2 units above it, continues
    down to 1); stop after `Settings.silencePauseMs` (Bulle slider 1–6 s, default 2.5 s) without speech, 15 s if none.
    The Android recognizer gets the same pause as its silence extras when not continuous
  - Whisper path (`Settings.engine == WHISPER` + downloaded model, else falls back to Android recognition):
    `app/src/main/cpp` (whisper.cpp v1.9.5 via CMake FetchContent, `whisper_jni.cpp`). arm64-v8a: shared libs with
    `GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS` → one `libggml-cpu-android_armv*.so` per ARM generation; `WhisperNative.
    ensureBackends` (called at service start and before the first model) runs `ggml_backend_load_all_from_path
    (nativeLibraryDir)`, which picks the best one (S24 Ultra: armv8.6_1 = dotprod+fp16+i8mm, measured ~2x faster than
    armv8.0: Small 14.6 s → 7.5 s for 4.7 s of audio). Needs `useLegacyPackaging = true` (libs extracted to disk) and
    `c++_shared`. ggml's log goes to logcat tag `VoiceTyperGgml`. armeabi-v7a: single static build. 4 threads (8 is slower:
    little cores),
    `WhisperNative` (JNI), `WhisperEngine` (one model kept loaded, preloaded by the service), `AudioCapture`
    (16 kHz AudioRecord, levels, silence auto-stop), `ModelManager` (download to filesDir/models, `.part` then rename).
    Silence (max level < 1) is never sent to Whisper; `Text.stripWhisperHallucinations` cleans the output;
    `Dictionary.whisperPrompt` = replacement targets as initial prompt. Catalog in core `Models.kt`
  - PC path (`Settings.engine == PC` + `pcAddress` = the PC's Tailscale name `<pc>.<tailnet>.ts.net`): records with
    `AudioCapture`, `PcEngine` POSTs a 16 kHz mono 16-bit WAV to `http://<name>:8766/transcribe?lang=fr|en` → `{"text"}`
    (`GET /health` for the connection test). The server is **FLASH LINGO's** `tools/whisper_server.py` (bound to
    127.0.0.1, exposed only by `tailscale serve --http 8766`, which answers 404 on the 100.x IP: use the name). No app-level
    auth: Tailscale is the access control and encryption. Max 1 min of audio (server limit) → longer dictations and any
    failure → Whisper on the phone (`downloadedWhisperFallback`) if a model is downloaded, else the error. Cleartext allowed
    (`network_security_config`). No VPN transport (`PcEngine.tailscaleUp`, ACCESS_NETWORK_STATE) → straight to the phone,
    no timeout; an instant connect failure (network switch) is retried once, a connect timeout is not. `PcHealth`: a throttled
    (25 s) background `/health` whenever the bubble shows warms the tunnel; if the last check failed < 90 s ago (or no VPN)
    the dictation uses Android real-time recognition right away instead of recording for the PC. A failed PC request
    falls back to the fastest downloaded model (base > tiny > small > turbo). Results go to the PC card's history. Do not add a server to this repo or to desktop Voice Typer (that app must never open a
    network port: IT requirement, work PC); one shared PC server serves FLASH LINGO and this app
  - `MicService` — foreground service type microphone while dictating (best effort); the recognizer starts 400 ms later
  - `SettingsRepository` — StateFlow shared by service and activity (same process), SharedPreferences JSON
  - `DiagnosticLog` — in-memory event log (never dictated text) shown/copied in the Diagnostic card (raw AudioRecord peak test + recognizer test)
  - `MainActivity` — single Compose screen: setup steps, client reply, diagnostic, language, presets, per-app, dictionary, signature, bubble, commands

## Build
- No Android SDK in cloud sessions (dl.google.com blocked): run core tests with `-PcoreOnly`; the APK is
  built by `.github/workflows/build.yml` (artifact + a GitHub release per new `versionName`, created by CI —
  cloud sessions may not push tags or create releases themselves). Release = bump `versionCode`/`versionName`.
- Signing: one fixed key from GitHub secrets `VT_KEYSTORE_B64` + `VT_KEYSTORE_PASSWORD` (alias `voicetyper`),
  never in the repo; without them CI signs with a random debug key and publishes no release. Never change the
  key (users would have to uninstall). ABIs: arm64-v8a + armeabi-v7a.
- Cloud sessions may not compile whisper.cpp (external code); the native part is only built by CI.
- Plugin versions live in `settings.gradle.kts` (`pluginManagement.plugins`), so `-PcoreOnly` never resolves AGP.
