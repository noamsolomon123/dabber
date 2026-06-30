All audit line numbers and BEFORE-text verified against the live files. The merges that matter (MainActivity line 98 is touched by two findings; BenchmarkActivity line 90 by two) are reconciled below into single patches.

---

# Dabber — Final Apply-Ready Fix Plan

Apply order priority: **C1 first** (it removes a permanent user lockout), then the rest of the CPU-critical block, then the NPU dependency swap, then the polish set. Patches are anchored to exact BEFORE text — line numbers are a guide; if an earlier patch in the same file shifts lines, match on text.

Deduplication notes folded in:
- Audit findings #1, #2, #11 are the **same** `startTurn` bug → one patch (**C1**).
- Findings #16 and #29 both rewrite `MainActivity` line 98 → one merged patch (**C9**).
- Findings #18 and #19 both need `@Volatile timerLabel` at `BenchmarkActivity:90` → declared once in **C11**, reused by **C12**.
- Finding #13 (CPU-critical) and #34 (other) both flip `OverlayService:95` to `START_NOT_STICKY` → the one-liner is in **C3**; #34's extra `startForeground` guard is in **O15**.

---

## 1. CPU-critical fixes (cpuCritical = true)

### OverlayService.kt — `com/dabber/overlay/OverlayService.kt`

#### C1 — Wrap the dictation worker so the bubble can never stay stuck amber (lines 364–386)
Root cause of the permanent lockout: if `transcribeClean()` throws (JNI OOM / null→NPE / any `Throwable`) after `WORKING` is posted, the IDLE/success post never runs, `onTap(WORKING)=no-op` ignores every further tap, and the infinite spinner animator keeps burning CPU. This single fix satisfies the "never stuck yellow" invariant for all record/transcribe failures and also guards the clipboard write (subsumes finding #2).

BEFORE:
```kotlin
        worker.execute {
            val pcm = recorder.record()
            main.post { enterState(State.WORKING) }
            val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
            main.post {
                if (text.isBlank()) {
                    toast(getString(R.string.no_speech))
                    enterState(State.IDLE)
                    return@post
                }
                val inserted = runCatching { InsertionService.instance?.insertText(text) }
                    .getOrNull() ?: false
                if (!inserted) {
                    copyToClipboard(text)
                    toast(getString(R.string.copied_clip))
                }
                playSuccess()
            }
        }
```
AFTER:
```kotlin
        worker.execute {
            try {
                val pcm = recorder.record()
                main.post { enterState(State.WORKING) }
                val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
                main.post {
                    if (text.isBlank()) {
                        toast(getString(R.string.no_speech))
                        enterState(State.IDLE)
                        return@post
                    }
                    val inserted = runCatching { InsertionService.instance?.insertText(text) }
                        .getOrNull() ?: false
                    if (!inserted) {
                        runCatching { copyToClipboard(text) }
                        toast(getString(R.string.copied_clip))
                    }
                    playSuccess()
                }
            } catch (t: Throwable) {
                main.post {
                    toast(getString(R.string.no_speech))
                    enterState(State.IDLE)
                }
            }
        }
```

#### C2 — Clamp the bubble to the screen so it can't be dragged off-screen (lines 172–179)
TYPE_APPLICATION_OVERLAY windows are not clamped by WindowManager; off-screen = invisible + untappable, recoverable only by restarting the service.

BEFORE:
```kotlin
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(root, lp) }
                }
```
AFTER:
```kotlin
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    val dm = resources.displayMetrics
                    lp.x = (startX + dx.toInt()).coerceIn(0, dm.widthPixels - lp.width)
                    lp.y = (startY + dy.toInt()).coerceIn(0, dm.heightPixels - lp.height)
                    runCatching { wm.updateViewLayout(root, lp) }
                }
```

#### C3 — Stop the OS from recreating the mic-FGS in the background (line 95)
START_STICKY recreates the microphone FGS with a null intent while backgrounded; on Android 14+ that throws SecurityException/ForegroundServiceStartNotAllowedException → crash-loop. The bubble is user-toggled, so silent auto-restart is also unwanted.

BEFORE:
```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
```
AFTER:
```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
```
(Pair with **O15**, which try/catches the `startForeground(MICROPHONE)` call itself.)

#### C4 — Release the mic when the service is destroyed mid-listen (lines 109–111)
`worker.shutdownNow()` only interrupts; the native `AudioRecord.read()` loop ignores interrupts and only exits on the polled `stopRequested` AtomicBoolean. Without this, the mic + thread stay held for up to 60 s after the service is gone.

BEFORE:
```kotlin
    override fun onDestroy() {
        serviceInstance = null
        main.removeCallbacks(hideRunnable)
```
AFTER:
```kotlin
    override fun onDestroy() {
        serviceInstance = null
        recorder.requestStop()          // unblock a listening record() loop; shutdownNow can't
        main.removeCallbacks(hideRunnable)
```

---

### InsertionService.kt — `com/dabber/a11y/InsertionService.kt`

#### C5 — Don't append after a field's HINT text (line 119)
On API 26+, an empty hinted field (WhatsApp/Telegram "Message", search boxes) exposes the hint via `getText()` with `isShowingHintText=true`, so dictation produces `"Message שלום"` instead of `"שלום"` — wrong output on the most common target, every day.

BEFORE:
```kotlin
        val current = node.text?.toString() ?: ""
```
AFTER:
```kotlin
        // Empty hinted fields expose the HINT via getText() with isShowingHintText=true
        // (AOSP TextView). Treat that as empty so we replace the hint instead of appending.
        val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
```

#### C6 — Verify SET_TEXT actually applied; fall through to clipboard on a confirmed no-op (line 137)
Compose `BasicTextField` and WebView `<input>` often return `true` from `ACTION_SET_TEXT` without applying it. The `||` short-circuits, the clipboard fallback never runs, and `startTurn` sees `inserted=true` and skips its safety net → green-success flash with nothing inserted. The `refresh()==true` guard avoids a false negative that would double-insert.

BEFORE:
```kotlin
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false

        val caret = (selStart + insert.length).coerceIn(0, merged.length)
```
AFTER:
```kotlin
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false

        // performAction can return true while Compose/WebView fields silently ignore SET_TEXT.
        // Re-read and only declare failure on a CONFIRMED no-op, so insertText() falls through
        // to the clipboard-paste path. Guard with refresh()==true to avoid a false negative
        // (which would double-insert via the paste fallback).
        if (node.refresh()) {
            val after = node.text?.toString().orEmpty()
            if (node.isShowingHintText || (after != merged && !after.contains(text))) return false
        }

        val caret = (selStart + insert.length).coerceIn(0, merged.length)
```

#### C7 — Recycle nodes in `findFocusedEditable` (lines 112–116)
`root` is never recycled, and a non-editable `focus` is dropped without recycle. This is the daily hot path; on API 26–32 unrecycled nodes exhaust the bounded pool ("leaked AccessibilityNodeInfo" / GC pressure).

BEFORE:
```kotlin
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focus != null && focus.isEditable) focus else null
    }
```
AFTER:
```kotlin
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        @Suppress("DEPRECATION") root.recycle()
        if (focus == null || !focus.isEditable) {
            @Suppress("DEPRECATION") focus?.recycle()
            return null
        }
        return focus
    }
```

#### C8 — Recycle `root` in `evaluateEditableFocus` (line 87)
Runs on every focus/window event and the throttled 250 ms content stream while typing; leaks one root node per call on API 26–32.

BEFORE:
```kotlin
        }.getOrDefault(false)

        if (editable != editableFocused) {
```
AFTER:
```kotlin
        }.getOrDefault(false)
        @Suppress("DEPRECATION") root.recycle()

        if (editable != editableFocused) {
```

---

### MainActivity.kt — `com/dabber/MainActivity.kt`

#### C9 — Gate the start buttons correctly + prevent concurrent `record()` on one AudioRecorder (MERGED findings #16 + #29)
Two problems on one line plus a re-entrancy hole:
- The bubble button isn't gated on a11y, so "Start floating bubble" succeeds with a toast but the bubble is permanently invisible (it can only show via `InsertionService.notifyEditableFocus`, unreachable when a11y is off). `a11yOk` is already computed at line 81.
- `refresh()` (fired by any `onResume`) re-enables `dictateBtn`/`bubbleBtn` while a scratchpad `record()` is in flight → a second concurrent `record()` on the same recorder (declared unsafe), garbled/duplicate audio, or a stuck button if the bg thread throws (no `try/finally`).

**(a)** Add a guard field next to `private val testRecorder = AudioRecorder()` (≈line 41):
```kotlin
    @Volatile private var dictating = false
```

**(b)** Lines 98–99. BEFORE:
```kotlin
        b.bubbleBtn.isEnabled = micOk && overlayOk && modelOk
        b.dictateBtn.isEnabled = micOk && modelOk
```
AFTER (a11y gate AND in-flight guard, both merged):
```kotlin
        b.bubbleBtn.isEnabled = micOk && overlayOk && modelOk && a11yOk && !dictating
        b.dictateBtn.isEnabled = micOk && modelOk && !dictating
```

**(c)** Rewrite `dictateIntoScratchpad()` (lines 302–322) to set the guard up front and always clear it in `finally`:
```kotlin
    private fun dictateIntoScratchpad() {
        if (!DictationCore.modelLoaded) { toast(getString(R.string.no_model)); return }
        if (!AudioRecorder.hasPermission(this)) { requestMic(); return }
        if (dictating) return
        dictating = true
        b.dictateBtn.isEnabled = false
        b.dictateBtn.text = getString(R.string.dictating)
        Thread {
            try {
                val pcm = testRecorder.record()
                runOnUiThread { b.dictateBtn.text = getString(R.string.transcribing) }
                val text = DictationCore.transcribeClean(pcm, ModelConfig.LANG)
                runOnUiThread {
                    if (text.isNotBlank()) {
                        val at = b.scratch.selectionStart.coerceIn(0, b.scratch.text.length)
                        b.scratch.text.insert(at, text)
                    } else {
                        toast(getString(R.string.no_speech))
                    }
                }
            } finally {
                runOnUiThread {
                    dictating = false
                    b.dictateBtn.text = getString(R.string.dictate_btn)
                    refresh()
                }
            }
        }.start()
    }
```

**(d)** Optional belt-and-braces in `toggleBubble()` (line 295) so the success toast never fires for an invisible bubble:
```kotlin
    private fun toggleBubble() {
        if (!isAccessibilityEnabled()) {
            toast(getString(R.string.perm_a11y_grant))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        OverlayService.start(this)
        toast(getString(R.string.bubble_started))
    }
```

#### C10 — Re-enable the download button when the model downloads but `loadModel()` fails (lines 152–156)
Currently a successful download + SHA but failed `loadModel()` (incompatible ggml / OOM / corrupt-but-right-size) leaves a visible "הורד מודל" button permanently disabled under a false "installed ✓". (`R.string.model_failed` takes one `%1$s`.)

BEFORE:
```kotlin
                DictationCore.loadModel(f.absolutePath)
                runOnUiThread {
                    b.modelProgress.text = getString(R.string.model_done)
                    refresh()
                }
```
AFTER:
```kotlin
                val loaded = DictationCore.loadModel(f.absolutePath)
                runOnUiThread {
                    b.modelProgressBar.visibility = View.GONE
                    if (loaded) {
                        b.modelProgress.text = getString(R.string.model_done)
                    } else {
                        b.modelProgress.text = getString(R.string.model_failed, "load")
                        b.downloadBtn.isEnabled = true
                    }
                    refresh()
                }
```

---

### BenchmarkActivity.kt — `com/dabber/bench/BenchmarkActivity.kt`

#### C11 — Live timer for the WER flow + make `timerLabel` thread-safe (line 90; lines 212–225)
WER never starts the elapsed timer, so the status freezes on a single "מתמלל i/N…" for the whole CPU transcribe (tens of seconds/clip) — violates the "never freeze / show a live timer" contract.

Line 90. BEFORE:
```kotlin
    private var timerLabel = ""
```
AFTER (shared with C12; now written from the executor thread, read from the UI ticker):
```kotlin
    @Volatile private var timerLabel = ""
```

`runOneWerModel` (lines 212–225). BEFORE:
```kotlin
    private fun runOneWerModel(variant: Variant): WerOutcome {
        return try {
            val result = BenchmarkRunner.run(this, variant) { msg ->
                runOnUiThread { if (running) setStatus(msg, R.color.muted) }
            }
            WerOutcome(variant.id, result, error = null)
        } catch (e: UnsatisfiedLinkError) {
            WerOutcome(variant.id, null, getString(R.string.bench_err_native))
        } catch (e: OutOfMemoryError) {
            WerOutcome(variant.id, null, getString(R.string.bench_err_oom))
        } catch (t: Throwable) {
            WerOutcome(variant.id, null, t.message ?: getString(R.string.bench_err_generic))
        }
    }
```
AFTER:
```kotlin
    private fun runOneWerModel(variant: Variant): WerOutcome {
        runOnUiThread { startElapsedTimer(variant.id) }
        return try {
            val result = BenchmarkRunner.run(this, variant) { msg ->
                timerLabel = "${variant.id} · $msg"   // keep the timer moving; surface clip progress
            }
            WerOutcome(variant.id, result, error = null)
        } catch (e: UnsatisfiedLinkError) {
            WerOutcome(variant.id, null, getString(R.string.bench_err_native))
        } catch (e: OutOfMemoryError) {
            WerOutcome(variant.id, null, getString(R.string.bench_err_oom))
        } catch (t: Throwable) {
            WerOutcome(variant.id, null, t.message ?: getString(R.string.bench_err_generic))
        } finally {
            runOnUiThread { stopElapsedTimer() }
        }
    }
```

#### C12 — Start the record-flow timer BEFORE `ensureModel` so SHA verification isn't shown as stale/wrong text (lines 166–174)
Today the timer starts only after `ensureModel()`, so the multi-second SHA-256 of already-cached models shows "recording… speak now" (model #1) or the previous model's last tick (#2/#3). (Depends on the `@Volatile timerLabel` from **C11**; `startElapsedTimer` already resets `timerStart` per call.)

BEFORE:
```kotlin
        return try {
            val modelFile = BenchmarkRunner.ensureModel(this, variant) { percent ->
                runOnUiThread {
                    if (running) setStatus(getString(R.string.bench_downloading, variant.id, percent), R.color.muted)
                }
            }
            runOnUiThread { startElapsedTimer(variant.id) }
            val (ms, text) = BenchmarkRunner.transcribePcm(modelFile, pcm)
            RecordResult(variant.id, ms, audioSec, text, error = null)
```
AFTER:
```kotlin
        runOnUiThread { startElapsedTimer(variant.id) }   // tick from the moment this model starts
        return try {
            val modelFile = BenchmarkRunner.ensureModel(this, variant) { percent ->
                timerLabel = getString(R.string.bench_downloading, variant.id, percent)
            }
            timerLabel = variant.id
            val (ms, text) = BenchmarkRunner.transcribePcm(modelFile, pcm)
            RecordResult(variant.id, ms, audioSec, text, error = null)
```

#### C13 — Don't show green "Done ✓" when every model failed (lines 147–151 and 194–198)
Both loops unconditionally set `bench_done` (green) even if all three rows errored. (`bench_failed` / `bench_err_generic` both exist.)

RECORD flow (147–151). BEFORE:
```kotlin
                for (variant in ModelVariants.ALL) {
                    val result = runOneRecordModel(variant, pcm, audioSec)
                    runOnUiThread { addRecordRow(result) }
                }
                runOnUiThread { setStatus(getString(R.string.bench_done), R.color.brand_success) }
```
AFTER:
```kotlin
                var anyOk = false
                for (variant in ModelVariants.ALL) {
                    val result = runOneRecordModel(variant, pcm, audioSec)
                    if (result.ok) anyOk = true
                    runOnUiThread { addRecordRow(result) }
                }
                runOnUiThread {
                    if (anyOk) setStatus(getString(R.string.bench_done), R.color.brand_success)
                    else setStatus(getString(R.string.bench_failed, getString(R.string.bench_err_generic)), R.color.brand_accent)
                }
```

WER flow (194–198). BEFORE:
```kotlin
                for (variant in ModelVariants.ALL) {
                    val outcome = runOneWerModel(variant)
                    runOnUiThread { addWerRow(outcome) }
                }
                runOnUiThread { setStatus(getString(R.string.bench_done), R.color.brand_success) }
```
AFTER:
```kotlin
                var anyOk = false
                for (variant in ModelVariants.ALL) {
                    val outcome = runOneWerModel(variant)
                    if (outcome.result != null) anyOk = true
                    runOnUiThread { addWerRow(outcome) }
                }
                runOnUiThread {
                    if (anyOk) setStatus(getString(R.string.bench_done), R.color.brand_success)
                    else setStatus(getString(R.string.bench_failed, getString(R.string.bench_err_generic)), R.color.brand_accent)
                }
```
(`result.ok` is the existing `error == null` accessor used elsewhere; if `RecordResult` lacks it, substitute `result.error == null`.)

#### C14 — Release the mic in `onDestroy` (lines 115–119)
Same un-interruptible `AudioRecord.read()` gap as C4: a rotate/Back during the up-to-60 s recording window holds the mic on a thread of the destroyed activity.

BEFORE:
```kotlin
    override fun onDestroy() {
        ui.removeCallbacks(timerTick)
        executor.shutdownNow()
        super.onDestroy()
    }
```
AFTER:
```kotlin
    override fun onDestroy() {
        ui.removeCallbacks(timerTick)
        recorder.requestStop()
        executor.shutdownNow()
        super.onDestroy()
    }
```

---

## 2. NPU fix — make the QNN context binaries actually load

Root cause (high): `onnxruntime-android-qnn:1.27.0`'s POM transitively pins `com.qualcomm.qti:qnn-runtime:2.42.0`, shipping QAIRT-2.42 `libQnnHtp/libQnnSystem/libQnnHtpV*Skel.so`. The AI Hub `*_qairt_context.bin` payloads were compiled with **QAIRT 2.45**, and QNN HTP context binaries are version-locked → `createSession` throws `QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES (1008)` / "Qnn System library version mismatch", `load()` returns false, NPU transcription "does nothing". No published ORT-QNN AAR bundles 2.45+, so override the transitive runtime.

The ORT AAR contains only `libonnxruntime.so` + `libonnxruntime4j_jni.so` (no `libQnn*.so`), and the `qnn-runtime:2.45.0` AAR supplies all `libQnn*` libs — so the swap is conflict-free (zero duplicate `.so`). `mavenCentral()` is already in `settings.gradle.kts`.

**`app/build.gradle.kts` line 67.** BEFORE:
```kotlin
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0")
```
AFTER:
```kotlin
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0") {
        // ORT 1.27.0 pins qnn-runtime 2.42.0; our AI Hub *_qairt_context.bin were built with
        // QAIRT 2.45 and will not deserialize on 2.42 HTP/System libs. Force-match 2.45.
        exclude(group = "com.qualcomm.qti", module = "qnn-runtime")
    }
    implementation("com.qualcomm.qti:qnn-runtime:2.45.0")
```
Equivalent strict form: `implementation("com.qualcomm.qti:qnn-runtime") { version { strictly("2.45.0") } }`.

Maven coordinates: `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0` (excluding `com.qualcomm.qti:qnn-runtime`) + `com.qualcomm.qti:qnn-runtime:2.45.0`. Available qnn-runtime versions on Maven Central: 2.42.0–2.47.0; use **2.45.0** to match the binaries (only move to 2.46/2.47 if you recompile the context at that same QAIRT — the `.bin` and the runtime `.so` must always match each other, never the EP).

Post-build verification (finding #28, no code-logic change): the ORT(2.42-built EP) + runtime(2.45) skew is the intended alignment but technically untested, so confirm on an arm64 Qualcomm device:
1. `adb logcat -s DabberQnn` + ORT's tag during `createSession`; a backend/API-version **warning that still reaches a successful session is fine**.
2. Optional extra diagnostics: in `makeSession()` add `opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)` before `createSession` so QNN version-negotiation lines surface to logcat/`lastError`.
3. Only if the 2.45 backend is hard-rejected: recompile the AI Hub context at the QAIRT that ORT 1.27 ships, OR step the override toward the EP's era while keeping the `.bin` compiled at that same QAIRT.

---

## 3. Other fixes (medium / low, cpuCritical = false)

**O15 — `OverlayService:407–411` (medium):** guard the mic foreground promotion so a missing RECORD_AUDIO permission stops the service instead of crashing (pairs with C3).
```kotlin
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            android.util.Log.w("OverlayService", "startForeground(microphone) denied; stopping", t)
            stopSelf(); return
        }
```

**O18 — `OverlayService:147–148` (low):** guard `addView` so a revoked overlay permission can't crash-loop the restarted service.
```kotlin
        if (runCatching { wm.addView(view, lp) }.isFailure) {
            stopSelf()
            return
        }
        root = view
```

**O5 — `OverlayService:191–196` (low):** re-assert opacity in `pressIn()` so a tap within the 180 ms fade-in doesn't freeze the bubble half-dim. Add `view.alpha = 1f` immediately after `view.animate().cancel()`.

**O2 — `InsertionService:148–153` (low):** restore the user's clipboard after the paste fallback (currently clobbered permanently).
```kotlin
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val prev = cm.primaryClip
        cm.setPrimaryClip(ClipData.newPlainText("dabber", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (prev != null) android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ runCatching { cm.setPrimaryClip(prev) } }, 500)
        return pasted
```

**O3 — `InsertionService:35` (low):** never replace the XML config with a blank `AccessibilityServiceInfo` (would drop `canRetrieveWindowContent`/`flagRetrieveInteractiveWindows` and silently kill the service).
```kotlin
            val info = serviceInfo ?: return@runCatching
```

**O4 — `InsertionService` (low, behavioral, no one-liner):** to honor "keyboard-only" visibility, AND `editable` (line 83) with `windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }` before notifying the overlay (Back dismisses the IME without clearing EditText focus). For the Compose caret (-1 selection → append-at-end), document as a known limitation rather than coercing.

**O1 — `WhisperEngine.kt:27–30` (low):** defensive empty-pcm guard at the native boundary (benchmark callers can pass a zero-length array).
```kotlin
        if (ctx == 0L || pcm.isEmpty()) return ""
```

**O6 — `MainActivity:157–162` (low):** hide the frozen determinate bar on a failed download. Add `b.modelProgressBar.visibility = View.GONE` as the first line inside the `catch` `runOnUiThread`.

**O17 — `QnnWhisperEngine.kt:150–243` (medium, NPU-only):** widen the cross-attention KV try/finally to cover allocation+encoder+decode so `crossK[]/crossV[]` (tens of MB each) aren't leaked on any throw between allocation and the existing inner try. Use one outer `try` from allocation; keep `melTensor`'s own inner `finally`; close all `crossK[i]/crossV[i]` in the outer `finally`.

**O7 — `ModelDownloader.kt:124` + `MainActivity.kt:146–151` + `NpuModel.kt:149,110–112` (medium):** emit a `-1` sentinel when there's no Content-Length so the UI flips to indeterminate instead of a frozen 0%. `onProgress(if (total > 0) 0 else -1)`; in MainActivity, `if (p < 0) b.modelProgressBar.isIndeterminate = true else { isIndeterminate=false; text=…; setProgressCompat(p,true) }`; guard NpuModel's formatter so `-1` isn't rendered as "-1%".

**O8 — `NpuModel.kt:99–103,118–121` + `ModelDownloader.kt:72` (medium):** skip the full SHA re-hash (~2.1 GB NPU / 834 MB CPU) on every call with a `<file>.ok` sidecar (`"$expected:${length()}"`), and show a "verifying" status when a hash is actually required. Add `<string name="bench_verifying">מאמת %1$s…</string>`. Minimal alternative: just emit `onProgress(bench_verifying, file.name)` immediately before the hash so the UI isn't frozen.

**O9 — `ModelDownloader.kt:163` + `NpuModel.kt:188` (low):** set `instanceFollowRedirects = false` so every redirect goes through the manual loop and `current` is the URL actually fetched (correct base for a relative Location); makes the advertised cross-protocol handling real.

**O10 — `NpuModel.kt` / `ModelDownloader.kt` download() (low):** add HTTP Range/resume — seed from `destination.length()`, send `Range: bytes=$have-`, accept `HTTP_PARTIAL` (206), open output in append mode, hash the existing bytes to seed the digest, and **stop deleting the `.part` on failure**. Minimum viable: just keep the `.part` on failure so a retry isn't a full restart (critical for the ~2.1 GB NPU set on cellular).

**O11 — `ModelConfig.kt` / `MainActivity:141` (low):** avoid storing the CPU model twice (benchmark `dabber-he-q8_0.bin` and install `dabber-he.bin` are byte-identical, same sha256). Either copy the cached benchmark file into `ModelConfig.FILE_NAME` before `ensure()`, or set `ModelConfig.FILE_NAME = "dabber-he-q8_0.bin"` so both paths share one file.

**O12 — `BenchmarkActivity.kt:247–265` (medium):** fast-fail the NPU card before the ~2.2 GB download on non-arm64 (emulator/non-Qualcomm). Right after the empty-pcm check:
```kotlin
if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
    runOnUiThread { setNpuStatus(getString(R.string.bench_npu_unsupported), R.color.brand_accent) }
    return@execute
}
```

**O16 — `app/build.gradle.kts` `android {}` (low):** drop the ~83 MB offline-prep lib (never used at runtime; this app loads precompiled EPContext binaries) to cut APK size.
```kotlin
    packaging {
        jniLibs {
            excludes += setOf("**/libQnnHtpPrepare.so")
        }
    }
```

**O13 — `activity_main.xml:625` (low, RTL):** scratchpad gravity `top|end` → `top|start` (in a force-RTL container `end` resolves LEFT; Hebrew should hug the right).

**O14 — `activity_main.xml:405–420` (low, cosmetic):** give `downloadBtn` state-aware ColorStateLists (or drop the flat `backgroundTint`/`textColor` and use `style="@style/Widget.Material3.Button"`) so it visibly dims while disabled.

**O19 — no fix (verified):** the x86_64 emulator is unaffected by the QNN dependency — both ORT engines `UnsatisfiedLinkError`→caught→"NPU not supported"; the daily whisper.cpp CPU path is built for x86_64 and runs. Only optional: re-add an ORT-CPU debug AAR per-ABI if you want the `onnx_wav` hook back on the emulator.

---

## 4. "Verify on emulator" checklist (CPU / whisper.cpp path)

1. **Build & install** the x86_64 debug APK on an Android emulator (API ≥ 26); confirm launch (no crash from debug hooks or `System.loadLibrary("dabber_whisper")`).
2. **Permissions:** grant Microphone, "Display over other apps", and enable the Dabber Accessibility service. Confirm the bubble button (`bubbleBtn`) is **disabled until a11y is on** (C9) and the model is loaded.
3. **Bubble visibility:** focus an `EditText` → bubble appears; Back to dismiss IME → (with O4) bubble hides; drag the bubble hard into every corner → it **stays on-screen and tappable** (C2).
4. **Happy turn:** tap bubble → LISTENING (waveform) → speak → WORKING (amber) → text inserted (or clipboard + "copied" toast); bubble returns to IDLE.
5. **Hint-text target:** dictate into an **empty** hinted field (chat/search) → output is just the dictated text, **not** "hint + text" (C5).
6. **Silent-insert fallback:** dictate into a Compose/WebView field → if SET_TEXT no-ops, the clipboard-paste fallback fires and you still get text or a "copied" toast (C6).
7. **Stuck-amber recovery:** force a transcribe failure (e.g. dictate with no model loaded path, or a very long clip to provoke OOM) → bubble **returns to IDLE**, a no-speech toast shows, and the **next tap works** (C1). Confirm no runaway spinner CPU after.
8. **Scratchpad re-entrancy:** start scratchpad dictation, then background/foreground the activity (lock/unlock) → the dictate button does **not** re-enable mid-record; no duplicate/garbled capture (C9).
9. **Download dead-end:** if `loadModel` fails after download, the "download" button is **re-enabled** and shows a failure (not a false "installed ✓") and the progress bar is hidden (C10, O6).
10. **Benchmark status motion:** run Record-all and WER → the elapsed timer **ticks during SHA verification and during each CPU transcribe** (C11, C12); if you force all three to fail, the headline is **not** a green "Done ✓" (C13).
11. **Lifecycle:** rotate the device or press Back **while recording** (bubble turn and benchmark) → mic is released promptly, no 60 s lingering capture (C4, C14).

---
# NPU research
Verified working as claimed (no fix needed): (1) empty/blank transcript -> toast(no_speech)+IDLE at OverlayService.kt:369-373; (2) record() is fully guarded (catches IllegalArgumentException/SecurityException/IllegalStateException, returns EMPTY) and its VAD loop always terminates (bounded by maxSamples, breaks on read<=0 or stopRequested) so there is no hang/stuck-LISTENING from record() itself; (3) insertion always gives feedback — InsertionService SET_TEXT -> clipboard PASTE -> outer clipboard+toast fallback; (4) JNI is memory-safe on the daily path: ReleaseStringUTFChars for both modelPath and lang, samples vector scoped, whisper_free on reload via WhisperEngine.load (ctx!=0 -> nativeFree then nativeInit); (5) thread-safety OK: DictationCore is process-wide @Synchronized so the non-thread-safe whisper_context is never re-entered, recorder.requestStop uses AtomicBoolean, state is @Volatile and only mutated on main. Research recipe: trace startTurn() worker -> identify any code between the WORKING post and the IDLE/success post that can throw; confirm onTap(WORKING)=no-op makes a stuck WORKING unrecoverable; cross-check that no enterState is called from focus/visibility paths. The single high-impact bug is the unwrapped worker (finding 1); fixing it satisfies the 'never stuck yellow' invariant for all of record/transcribe failures.

Verified against: minSdk=26 (app/build.gradle.kts line 12) so isShowingHintText/hintText/refresh() exist on all targets; accessibility_service_config.xml has canRetrieveWindowContent=true + flagRetrieveInteractiveWindows. Call path confirmed: OverlayService.startTurn (lines 378-383) treats insertText()==true as 'done' and only does clipboard-copy+toast on false — so any false-positive return from insertViaSetText produces a total silent no-op. The two HIGH findings (hint-text append, no SET_TEXT verification) are the ones that break the everyday whisper.cpp/CPU dictation experience; the leaks are real but degrade gradually on API 26-32. Recommended order to ship: finding 1, then 2, then 3/4 together.

Verified-correct (no fix needed): strict keyboard-only show via computeShouldShow()=state!=IDLE||lastEditableFocused; 0.8s debounce (HIDE_DELAY_MS=800 + hideRunnable re-check); FLAG_NOT_TOUCHABLE consistent with windowShown (showWindow sets BASE_FLAGS, hideWindow's withEndAction restores NOT_TOUCHABLE; animate().cancel() correctly suppresses the stale withEndAction so flags never get stuck touchable-while-hidden); waveform/spinner animator cancelled in setMode/stop/onDestroy (only runs in LISTENING/WORKING which can't be hidden); a11y connect re-eval via InsertionService.onServiceConnected->evaluateEditableFocus + OverlayService.addBubble's forceEvaluate(); accessibility_service_config.xml has canRetrieveWindowContent=true + flagRetrieveInteractiveWindows so focus detection works. Research recipe: read OverlayService.kt then trace each collaborator that drives it — InsertionService.kt (visibility bridge + a11y connect), AudioRecorder.record (confirm it returns EMPTY not throws), DictationCore.transcribeClean + WhisperEngine.nativeTranscribe (the throw source for finding 1), MainActivity (start gating), AndroidManifest + build.gradle.kts (targetSdk 35 + microphone FGS type for finding 3). One lower-confidence item I did not file as a fix: InsertionService.evaluateEditableFocus() early-returns and keeps the last editableFocused when rootInActiveWindow is transiently null (anti-flicker); in the rare case the focused window closes without a subsequent non-null re-eval, the bubble can stay visible+touchable over the app until the next event corrects it.

VERIFIED SAFE (no fix needed), per the task's explicit asks:\n\n1) Debug hooks do NOT crash a normal launch. onCreate lines 65-67 read intent.getStringExtra(\"transcribe_wav\"/\"onnx_wav\"/\"qnn_wav\"); on a LAUNCHER launch these return null so the ?.let{} bodies never run. The ORT classes com.dabber.npu.OnnxWhisperEngine / QnnWhisperEngine (which import ai.onnxruntime.*) are only class-loaded when their hook actually fires, so the arm64-only onnxruntime-android-qnn AAR (no x86_64 libs, per app/build.gradle.kts:62-67) cannot break a normal emulator/phone launch. The hooks each run on their own Thread and swallow failures (Log.e + return), so even when invoked with a bad path they don't take down the UI.\n\n2) Native whisper.cpp load is safe on the CPU path. WhisperEngine's companion init runs System.loadLibrary(\"dabber_whisper\") the first time DictationCore is touched (refresh() -> DictationCore.modelLoaded, every onResume). CMakeLists builds add_library(dabber_whisper SHARED ...) and abiFilters = [arm64-v8a, x86_64] (build.gradle.kts:19), so the .so is packaged for both the phone and the emulator; no UnsatisfiedLinkError on launch. (Caveat, low: an armeabi-v7a-only device would have no matching lib and crash at first onResume, but abiFilters intentionally excludes it.)\n\n3) Accessibility detection works: isAccessibilityEnabled() (lines 185-193) matches entries starting with \"com.dabber/\" containing \"InsertionService\"; the real component is com.dabber/com.dabber.a11y.InsertionService -> matches.\n\n4) String formats are crash-free: model_downloading uses %1$d (Int p), model_failed uses %1$s, model_done/model_loaded/model_missing take no args — all match their getString() call sites.\n\nRESEARCH RECIPE: trace re-enable vectors for any button disabled inside a bg-thread op by grepping every caller of refresh() (onResume, permLauncher, maybeLoadModel, downloadModel) and confirming none can flip isEnabled back while the op is in flight; and trace every early-return/exception path of a bg Thread to confirm the disabled control is always restored (prefer try/finally + a @Volatile in-progress guard checked inside refresh()).

Verified SOLID (no fix needed): (1) Per-model isolation is correct — runOneRecordModel (165-184) and runOneWerModel (212-225) each catch UnsatisfiedLinkError/OutOfMemoryError/Throwable and return a failure row, so one model's crash never aborts the others. (2) finally{endRun()} (158-160, 205-207, 277-279) plus endRun() (327-335) always re-enables all three buttons, hides both progress bars, stops the timer and clears the running flag — the UI cannot stay stuck on 'מכין…' after a run ends. (3) WhisperEngine load failure → IOException, caught. (4) Empty recording → early return + finally, no freeze. (5) HebrewWer is correct: NFKD decompose, strip U+0591..U+05C7 combining marks, fold geresh/gershayim, two rolling-row word-level Levenshtein returns prev[m] correctly; no off-by-one or wrong-output. (6) RecordResult.rtf / VariantResult.totalRtf guard against divide-by-zero. (7) audioSec = pcm.size / SAMPLE_RATE_HZ (16_000.0) is correct Double division. (8) All referenced string resources exist with matching format specifiers (checked res/values/strings.xml) and all layout binding ids exist (activity_benchmark.xml). Research recipe: read the 4 bench files, then their deps — audio/AudioRecorder.kt, asr/WhisperEngine.kt, model/ModelDownloader.kt, audio/WavReader.kt, npu/NpuModel.kt — plus res/layout/activity_benchmark.xml, res/values/strings.xml, and confirm assets/bench/{refs.json,*.wav} exist. The four findings all stem from status/timer wiring (live timer absent in WER; timer started too late in record; unconditional green 'done'; UI callbacks outside the worker try/catch), not from the inference/scoring core which is sound.

Verified out of scope as bugs: (1) contentLengthLong is API-24 and minSdk=26, so no NoSuchMethodError crash. (2) ModelConfig.SHA256 is NOT a placeholder — it equals the benchmark's q8_0 variant sha256 (ModelVariants.kt:52), so CPU downloads won't universally fail the digest check. (3) .part cleanup itself is correct (deleted before download and on failure) and the atomic rename + finalFile.delete() before rename are sound; renames stay within one filesystem. (4) Dest dirs are correct and consistent: ModelDownloader/ModelStore use filesDir/models, NpuModel uses filesDir/npu-qnn; ModelStore also falls back to externalFilesDir/models for sideload. (5) IOExceptions ARE surfaced: MainActivity.downloadModel catch(Exception) -> R.string.model_failed; BenchmarkActivity.startNpuRun catch(Throwable) -> R.string.bench_failed; both downloaders are invoked off the main thread (Thread / executor). (6) gzip/Transfer-Encoding edge: percent is coerceIn(0,100) and SHA is over the decoded body, so no crash/wrong-hash. NOTE: none of the audited download code runs on the daily CPU dictation hot path (that path = ModelStore.modelFile existence check + DictationCore one-time load), which is why every finding is cpuCritical=false despite affecting first-run setup.

DECODE-LOOP VERIFICATION (vs quic/ai-hub-models src/qai_hub_models/models/_shared/hf_whisper/{app.py,model.py}, fetched and read): NO BUGS. The Kotlin port in QnnWhisperEngine.transcribe is faithful:\n- Constants match: MEAN_DECODE_LEN=200 (model.py:46), MASK_NEG=-100.0 (model.py:55), self-KV length=199, attn length=200.\n- Tensor names/shapes/dtypes match model.py get_input_spec: input_ids int32 (1,1); attention_mask float32 (1,1,1,200); k_cache_self_{i}_in float32 (20,1,64,199); v_cache_self_{i}_in float32 (20,1,199,64); k_cache_cross_{i} float32 (20,1,64,1500); v_cache_cross_{i} float32 (20,1,1500,64); position_ids int32 (1,); outputs logits [1,51865,1,1] + k/v_cache_self_{i}_out. The engine resolves all names dynamically and resolves ids/pos int32-vs-int64 from the graph, so it matches the real export. numLayers=4 derived from k_cache_self_*_in regex (large-v3-turbo decoder_layers=4).\n- Mask reveal matches app.py:171 `attention_mask[...,mean_decode_len-n-1]=0.0` -> Kotlin `attn[MEAN_DECODE_LEN-n-1]=0f` (cumulative, right-aligned, index 0 never revealed). Loop bound `for n in 0 until 199` == range(mean_decode_len-1). position_ids==n (init 0, +1 each non-break step) matches app.py:136/211. Self-KV threaded out->in (copyInto) matches app.py:191/194. Stop condition `isLast(n+1==199) || nextId==eot` matches app.py:204. Teacher-forced prompt: Kotlin appends generated tokens only when `n>=promptLen-1` (promptLen=4 = output_length), the exact analogue of app.py:207 `if n >= output_length-1`; AI Hub seeds [sot] only (output_length=1) while Dabber forces [sot,<|he|>,transcribe,notimestamps] — a documented, correct adaptation. argmax over the flat [1,V,1,1] buffer == torch.argmax(logits,1) since total elements==V and contiguous. Cross-KV created once and reused/closed correctly; per-step self/mask/id tensors closed in finally. IO is float32 as verified, so the floatBuffer reads are correct (the doc's FP16 caveat does not apply to this export).\n\nVERSION RESEARCH (hard facts):\n- onnxruntime-android-qnn 1.26.0 AND 1.27.0 both pin com.qualcomm.qti:qnn-runtime 2.42.0 (POMs read directly). 1.23.2 -> 2.37.1. Latest onnxruntime-android-qnn = 1.27.0 (no AAR bundles 2.45+).\n- com.qualcomm.qti:qnn-runtime available versions include 2.42.0, 2.43.0, 2.44.0, 2.45.0, 2.46.0, 2.47.0 (latest) on Maven Central. Pick 2.45.0 to match the AI Hub QAIRT 2.45 used to compile the .bin (use 2.46.0/2.47.0 only if you recompile the context at that same QAIRT).\n- qnn-runtime 2.45.0 AAR (65,891,759 bytes) jni/arm64-v8a includes: libQnnHtp.so, libQnnSystem.so, libQnnHtpPrepare.so, libQnnHtpV68/V69/V73/V75/V79/V81 Skel+Stub, libQnnDspV66Skel.so.\n- onnxruntime-android-qnn 1.27.0 AAR (8,008,147 bytes) jni/arm64-v8a includes ONLY libonnxruntime.so + libonnxruntime4j_jni.so => overriding qnn-runtime cleanly replaces every QNN/HTP .so with zero duplicates.\n- QNN context binaries are NOT backward/forward compatible across QAIRT minors; a 2.45 context will NOT run on a 2.42 System/HTP library (evidence below). Aligning the runtime .so to the binary's QAIRT is the supported fix.\n\nMaven coordinates for the fix:\n- implementation(\"com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0\") { exclude(group=\"com.qualcomm.qti\", module=\"qnn-runtime\") }\n- implementation(\"com.qualcomm.qti:qnn-runtime:2.45.0\")\n\nSources / URLs:\n- POM (proves 2.42.0 pin): https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.27.0/onnxruntime-android-qnn-1.27.0.pom\n- qnn-runtime versions: https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/maven-metadata.xml | https://central.sonatype.com/artifact/com.qualcomm.qti/qnn-runtime\n- ORT QNN EP docs (provider options htp_performance_mode / enable_htp_fp16_precision / EPContext): https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html\n- Context-binary version-lock failure evidence: https://github.com/google-ai-edge/LiteRT-LM/issues/2226 ('Qnn System library version ... is mismatched'); https://github.com/microsoft/onnxruntime/issues/23171 and https://github.com/microsoft/onnxruntime/issues/26163 (QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES across ORT/QNN combos).\n- AI Hub QAIRT versions (2.41/2.42 default/2.43.1): https://app.aihub.qualcomm.com/docs/hub/release_notes.html\n- AI Hub reference decode loop verified against: https://github.com/quic/ai-hub-models -> src/qai_hub_models/models/_shared/hf_whisper/app.py (lines 132-213) and model.py (lines 46,55,237-277).

Audit recipe / what was verified clean (no findings): (1) Missing resources — grepped every R.string/R.color/R.drawable/R.style in app/src/main/java and every @string/@color/@drawable in res/*.xml; ALL resolve. NpuModel.kt:101/111/121 reuse R.string.bench_downloading correctly (String name + Int percent). (2) Format args — model_downloading `%1$d%%`←Int OK; model_failed/bench_failed/bench_model_failed/bench_device_model `%1$s`←String OK; bench_downloading `%1$s,%2$d`←(String,Int) OK; bench_running_elapsed `%1$s,%2$d`←(String,Long) OK; bench_clip_line `%1$s,%2$d,%3$s,%4$s`←(name:String, clip.ms:Long, sec():String, pct():String) OK; bench_npu_result `%1$s`←sec():String OK. No IllegalFormatConversion risk. (3) Dark mode — values/colors.xml and values-night/colors.xml define the SAME 21 color names (full parity); themes day vs night correctly flip windowLightStatusBar/windowLightNavigationBar (true→false) with matching dark app_background; no hardcoded text colors in layouts that would break night (only #33FFFFFF ripple/overlay-chip on indigo, fine both themes). (4) Inflate-crash — Material 1.12.0 (libs.versions.toml) supports colorSurfaceContainer used in both themes; AppCompatActivity + Theme.Material3.DayNight.NoActionBar is compatible with the Material3 widget styles; minSdk=26 covers importantForAutofill(26)/windowLightStatusBar(23)/windowLightNavigationBar(27, guarded by tools:targetApi). (5) Drawables — bg_hero/bg_hero_chip/bg_icon_chip/bg_scratch/bubble_bg + all ic_* vectors are valid; ic_arrow_back has autoMirrored=true (correct for RTL back); ic_launcher used as both app icon and notification small icon. Files read: res/layout/activity_main.xml, res/layout/activity_benchmark.xml, res/values/{strings,colors,themes}.xml, res/values-night/{colors,themes}.xml, all res/drawable/*.xml, res/xml/accessibility_service_config.xml, MainActivity.kt, bench/BenchmarkActivity.kt, bench/BenchmarkRunner.kt, bench/ModelVariants.kt, overlay/OverlayService.kt, a11y/InsertionService.kt, model/ModelConfig.kt, npu/NpuModel.kt, npu/QnnWhisperEngine.kt, AndroidManifest.xml, app/build.gradle.kts, gradle/libs.versions.toml.

Verified OK (no action): (1) versionCode=6, versionName=\"0.3.2\" valid. (2) viewBinding=true is correct and used — MainActivity uses ActivityMainBinding, BenchmarkActivity uses ActivityBenchmarkBinding (both layouts exist). (3) Permissions complete and correct for the features: RECORD_AUDIO, SYSTEM_ALERT_WINDOW (overlay TYPE_APPLICATION_OVERLAY), FOREGROUND_SERVICE + FOREGROUND_SERVICE_MICROPHONE (mic FGS), POST_NOTIFICATIONS (requested at runtime on API33+), INTERNET (ModelDownloader). (4) All four manifest components resolve to real classes: .MainActivity, .bench.BenchmarkActivity (exported=false, launched internally — fine), .overlay.OverlayService (mic FGS), .a11y.InsertionService. (5) Accessibility service is well-formed: android:permission=BIND_ACCESSIBILITY_SERVICE, intent-filter action android.accessibilityservice.AccessibilityService, meta-data -> @xml/accessibility_service_config (file present and valid), @string/a11y_description present. exported=\"false\" is fine for an a11y service — system_server (SYSTEM uid) both enumerates (queryIntentServices is not exported-filtered for system) and binds non-exported services, so discovery/enable works. (6) Theme.Dabber (Material3.DayNight.NoActionBar) resolves; material dep present. (7) compileSdk=35 / AGP 8.7.1 / Kotlin 2.0.21 / minSdk 26 — compatible. (8) externalNativeBuild split (cmake.path at android level, cmake.arguments + abiFilters at defaultConfig) is structured correctly. GROUND TRUTH: app-debug.apk (227 MB, built Jun 30) exists with lib/arm64-v8a (full ORT+QNN+whisper) and lib/x86_64 (whisper only) — so both files build, install, and run; nothing in them breaks the CPU/whisper.cpp daily experience. No cpuCritical=true issues found in the two audited files.

Audited but found CLEAN (no fix needed): (1) OnnxWhisperEngine.transcribe (npu) — every native handle is closed: melTensor in finally (113-115), per-step idsTensor in finally (162-164), encHidden in outer finally (170-172), and all `res.get(...).get()` tensors are owned by the `dec.run(...).use{}` Result; no leak even on the decode-loop error path. close() correctly leaves the shared OrtEnvironment singleton intact. (2) AudioRecorder.record() — AudioRecord is always stop()+release()'d in its finally on every exit (init-fail, start-fail, read-error, VAD-stop, requestStop). (3) Executors — both single-thread pools (OverlayService.worker, BenchmarkActivity.executor) are shutdownNow()'d in their respective onDestroy. (4) Handlers — OverlayService.hideRunnable and BenchmarkActivity.timerTick are removeCallbacks'd in onDestroy. (5) DictationCore is @Synchronized and shared on a single worker thread, so no concurrent native whisper.cpp calls. (6) ServiceInstance static is nulled in onDestroy (no static Activity/Service leak).\n\nThreading model is sound overall: transcription (DictationCore.transcribeClean / WhisperEngine.transcribe) and AudioRecorder.record() always run off the main thread via worker/executor/raw Thread; only short UI updates are main.post/runOnUiThread. One minor note (not filed as a bug): OverlayService dispatches InsertionService.insertText() on the main thread (inside main.post in startTurn ~378); accessibility ACTION_SET_TEXT on a very large target field could briefly stutter, but it is not a blocking I/O call.\n\nThe two cpuCritical findings (#1, #3) share the same root cause: shutdownNow()/interrupt cannot break a native AudioRecord.read() loop — only AudioRecorder.requestStop() (the polled AtomicBoolean) can. Both fixes are one line each.