# Dabber — Hebrew-first on-device voice dictation (design)

**Date:** 2026-06-29
**Status:** Approved (design), pre-implementation
**Owner:** noams

## 1. Goal

A free, fully on-device ("local") Android voice-to-text dictation app, functionally
equivalent to Wispr Flow but **specialized to be the best available for Hebrew**.
The app lets the user press a floating button on top of *any* app, speak, and have the
transcribed (and lightly cleaned-up) Hebrew text inserted into the focused text field.

Distributed as an APK from a public GitHub repo (GitHub Releases), built by CI.

### Non-goals / explicit constraints
- **Not** a 1:1 brand clone of Wispr Flow. Own name + own icon. We match *function and
  polish*, not the trademarked logo/name (brand impersonation → repo takedown risk).
- No cloud transcription. 100% offline inference. The only network use is the one-time
  model download from the GitHub release (with checksum).
- Target device: OnePlus 15, 16 GB RAM, arm64-v8a, Android 15/16 class. Primary build
  target ABI: `arm64-v8a`. (x86_64 only if cheap, for emulator.)

## 2. Decisions (from brainstorming)
- **Trigger UX:** Floating overlay bubble + Accessibility service (press-anywhere).
- **Cleanup:** Light, rule-based, fully offline (no local LLM in v1).
- **Speed/accuracy:** Balanced — best Hebrew accuracy that still returns within ~1–2 s
  after the user stops talking.
- **Inference engine:** whisper.cpp (ggml) via JNI. ONNX/NPU path is a benchmark
  spot-check, adopted only if it is a clear, reliable win.
- **App name (proposed):** "דַּבֵּר / Dabber" (renameable).

## 3. Architecture

Six independently testable units.

1. **OverlayService** (`foreground service` + `SYSTEM_ALERT_WINDOW`)
   - Draws the floating mic bubble over all apps; draggable; remembers position.
   - State machine: `idle → listening → transcribing → inserting → idle`.
   - Tap (or press-and-hold, configurable) starts/stops a dictation turn.
   - Owns the foreground-service notification (required for mic capture in background).

2. **AudioRecorder**
   - `AudioRecord`, 16 kHz mono PCM16 (whisper's native input rate).
   - Voice-activity detection (energy/WebRTC-VAD style) to auto-stop on trailing silence.
   - Emits a finished PCM buffer to WhisperEngine. Hard cap (e.g. 60 s) per turn.

3. **WhisperEngine** (JNI → whisper.cpp / ggml)
   - Native lib built with NDK + CMake for `arm64-v8a` (and `x86_64` for emulator).
   - Loads the selected Hebrew ggml model from app files dir; runs transcription with
     `language=he` (or auto); returns `{text, ms, segments}`.
   - Optional Vulkan/GPU backend flag, decided by benchmark.

4. **TextCleaner** (pure Kotlin, unit-tested)
   - Strip fillers (אהה / אממ / emm), collapse immediate word repeats, normalize spaces
     around punctuation, trim, Hebrew-aware (no niqqud insertion, keep final letters as
     produced). Toggleable. No network, deterministic.

5. **InsertionService** (`AccessibilityService`)
   - Finds the focused editable node in the foreground app.
   - Inserts via `ACTION_SET_TEXT` (append at cursor / replace selection), with a
     **clipboard + `ACTION_PASTE` fallback** for apps that block SET_TEXT.
   - Handles RTL correctly; never reorders Hebrew.

6. **App UI** (single-activity, Compose or Views)
   - Onboarding: request the 3 permissions in order (mic, draw-over-apps, accessibility)
     with clear Hebrew explanations and deep links to the system screens.
   - Model manager: shows model, size, download progress, checksum verify, re-download.
   - Settings: language (he / en / auto), bubble position + size, VAD sensitivity,
     cleanup toggles, tap-vs-hold, GPU on/off.
   - Built-in scratchpad screen to test dictation without leaving the app.

### Data flow
`tap bubble → AudioRecorder (VAD auto-stop) → PCM16 → WhisperEngine (whisper.cpp) →`
`raw Hebrew text → TextCleaner → InsertionService → focused field → bubble idle`

### Permissions
`RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MICROPHONE`, `BIND_ACCESSIBILITY_SERVICE`, `POST_NOTIFICATIONS`,
`INTERNET` (model download only).

## 4. Phase 0 — Hebrew model benchmark (PC) — picks the shipped model

This is the concrete form of "test whisper ivrit / find the best model". Output drives
the WhisperEngine model choice. Runs natively on the PC (faster/more accurate than in an
emulator), parallel agents per model family.

- **Eval data:** a Hebrew test set with ground-truth transcripts — candidates:
  ivrit-ai eval slice, Mozilla Common Voice `he`, Google FLEURS `he_il`. Pick a small,
  license-clean subset (~30–60 short clips + a few long-form) and reuse it across models.
- **Candidate models:**
  - Vanilla Whisper: `base`, `small`, `medium`, `large-v3`, `large-v3-turbo`.
  - ivrit-ai Hebrew fine-tunes (turbo / large-v3 variants).
  - (Spot-check) one NPU/ONNX path and optionally a non-Whisper Hebrew ASR.
- **Metrics:** WER + CER (Hebrew-normalized), and Real-Time-Factor / latency on a
  representative CPU. Report a table; recommend the best **accuracy-within-balanced-latency**
  model that is **convertible to ggml** for on-device use.
- **Hard output:** chosen model + its ggml file (converted/quantized) + checksum, plus a
  written comparison `docs/benchmarks/hebrew-asr-<date>.md`.
- **Risk handling:** if the single best model can't convert to ggml, ship the best one
  that can; record the gap.

## 5. Phases 1–3

- **Phase 1 — Working dictation in emulator:** Gradle skeleton; build whisper.cpp via
  NDK; OverlayService + AudioRecorder + WhisperEngine + InsertionService end-to-end;
  prove dictation inserts Hebrew text into a sample app in a high-end Android emulator
  (Android 15/16, large RAM). x86_64 ggml build for emulator validation.
- **Phase 2 — Polish:** onboarding flow, model download + checksum, settings, TextCleaner
  rules, app icon + Hebrew strings, scratchpad.
- **Phase 3 — Ship:** GitHub repo; GitHub Actions workflow builds + signs a release APK on
  tag; uploads APK + model to GitHub Releases; Hebrew README with install + permission
  steps.

## 6. Testing
- **Phase 0 harness** (Python): downloads eval set, runs models, computes WER/CER/RTF,
  emits the comparison doc. Reproducible.
- **Unit tests:** TextCleaner (filler/repeat/spacing cases, Hebrew + mixed he/en).
- **Emulator smoke test:** install APK → overlay shows → records → transcribes → inserts
  into a target app; model download + checksum path.

## 7. Open risks
- ggml conversion availability for the very best ivrit-ai model (mitigation in §4).
- Per-app Accessibility insertion quirks (mitigation: clipboard-paste fallback).
- Emulator mic input fidelity (mitigation: feed a known WAV via emulator/host mic, and
  rely on PC benchmark for accuracy truth).
- APK + model size on Releases (model downloaded separately, not in APK).

## 8. Deliverables
1. Public GitHub repo with the Android app source + CI.
2. Release APK (arm64-v8a) the user can install on the OnePlus 15.
3. The selected Hebrew ggml model published in the release.
4. Hebrew model benchmark comparison doc.
5. Hebrew README (install + grant-permissions guide).
