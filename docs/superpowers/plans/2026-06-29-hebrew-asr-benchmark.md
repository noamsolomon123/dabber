# Hebrew ASR Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Empirically pick the best Hebrew speech-to-text model for on-device use, output a comparison doc and the chosen model converted to whisper.cpp ggml + quantized, with a verified checksum.

**Architecture:** A reproducible Python harness fetches a fixed Hebrew eval set (audio + reference transcripts), runs each candidate model on it, scores Hebrew-normalized WER/CER and real-time-factor, and writes a ranked report. The winning model is converted to ggml and validated against whisper.cpp so it is guaranteed to run in the Android app.

**Tech Stack:** Python 3.10 (`py -3.10`), `faster-whisper` (CTranslate2, CUDA on RTX 3060), `transformers` (for ivrit-ai HF checkpoints), `jiwer` (WER/CER), `datasets`/`soundfile`/`librosa` (eval data), `ffmpeg`, and `whisper.cpp` for ggml conversion/quantization.

**Environment:** Windows, RTX 3060 12 GB (CUDA), repo root `C:\Users\noams\dabber`. All commands run from repo root unless noted. Use `py -3.10` to pin the interpreter.

---

## File Structure

- `benchmark/requirements.txt` — pinned deps.
- `benchmark/normalize_he.py` — Hebrew text normalization for fair scoring (pure, tested).
- `benchmark/metrics.py` — WER/CER on normalized text (pure, tested).
- `benchmark/datasets.py` — fetch/prepare the fixed eval set → list of `(wav_path, reference)`.
- `benchmark/models.py` — model registry + per-backend `transcribe()` runners.
- `benchmark/run_benchmark.py` — orchestrator → `benchmark/results/results.json` + `docs/benchmarks/hebrew-asr-2026-06-29.md`.
- `benchmark/convert_to_ggml.py` — convert+quantize the winner via whisper.cpp, emit checksum.
- `benchmark/tests/test_normalize_he.py`, `benchmark/tests/test_metrics.py` — unit tests.
- `benchmark/data/` (gitignored) — downloaded audio. `benchmark/results/` — scores.

Files split by responsibility: normalization, metrics, data, model-running, orchestration, and conversion are each one concern and independently testable.

---

## Task 1: Python env + dependencies

**Files:**
- Create: `benchmark/requirements.txt`

- [ ] **Step 1: Write `benchmark/requirements.txt`**

```text
faster-whisper==1.0.3
transformers==4.44.2
torch==2.4.1
torchaudio==2.4.1
jiwer==3.0.4
datasets==2.21.0
soundfile==0.12.1
librosa==0.10.2.post1
huggingface-hub==0.24.6
tqdm==4.66.5
```

- [ ] **Step 2: Create venv and install (CUDA torch)**

Run:
```bash
py -3.10 -m venv benchmark/.venv
benchmark/.venv/Scripts/python.exe -m pip install --upgrade pip
benchmark/.venv/Scripts/python.exe -m pip install torch==2.4.1 torchaudio==2.4.1 --index-url https://download.pytorch.org/whl/cu121
benchmark/.venv/Scripts/python.exe -m pip install -r benchmark/requirements.txt
```
Expected: installs succeed; no resolver errors.

- [ ] **Step 3: Verify CUDA is visible to torch**

Run:
```bash
benchmark/.venv/Scripts/python.exe -c "import torch;print('cuda',torch.cuda.is_available(),torch.cuda.get_device_name(0))"
```
Expected: `cuda True NVIDIA GeForce RTX 3060`

- [ ] **Step 4: Commit**

```bash
git add benchmark/requirements.txt
git commit -m "chore(bench): pin Hebrew ASR benchmark deps"
```

---

## Task 2: Hebrew text normalization (TDD)

Fair WER needs both hypothesis and reference normalized the same way: strip niqqud, strip punctuation, normalize whitespace, unify Hebrew geresh/gershayim and final/■ forms left as-is (final letters are real spelling — do NOT fold them).

**Files:**
- Create: `benchmark/normalize_he.py`
- Test: `benchmark/tests/test_normalize_he.py`

- [ ] **Step 1: Write the failing test**

```python
# benchmark/tests/test_normalize_he.py
from benchmark.normalize_he import normalize_he

def test_strips_niqqud():
    assert normalize_he("שָׁלוֹם") == "שלום"

def test_strips_punctuation_and_collapses_space():
    assert normalize_he("שלום,  עולם!") == "שלום עולם"

def test_keeps_final_letters():
    # final mem must stay final mem, not fold to regular mem
    assert normalize_he("עולם") == "עולם"

def test_normalizes_geresh_gershayim_to_ascii():
    assert normalize_he("צה״ל ד׳") == "צה\"ל ד'"

def test_lowercases_latin_for_mixed():
    assert normalize_he("Hello שלום") == "hello שלום"

def test_strips_leading_trailing_space():
    assert normalize_he("  היי  ") == "היי"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `benchmark/.venv/Scripts/python.exe -m pytest benchmark/tests/test_normalize_he.py -v`
Expected: FAIL with `ModuleNotFoundError: benchmark.normalize_he`

- [ ] **Step 3: Write minimal implementation**

```python
# benchmark/normalize_he.py
import re
import unicodedata

# Hebrew niqqud (combining points) U+0591–U+05C7 except letters; also cantillation.
_NIQQUD = re.compile(r"[֑-ׇֽֿׁׂׅׄ]")
_GERESH = "׳"      # ׳ -> '
_GERSHAYIM = "״"   # ״ -> "
# keep letters (incl. final forms) + ascii letters/digits + apostrophe/quote
_KEEP = re.compile(r"[^a-z0-9א-ת'\"\s]")

def normalize_he(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    text = _NIQQUD.sub("", text)
    text = text.replace(_GERESH, "'").replace(_GERSHAYIM, '"')
    text = text.lower()
    text = _KEEP.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text
```

- [ ] **Step 4: Run test to verify it passes**

Run: `benchmark/.venv/Scripts/python.exe -m pytest benchmark/tests/test_normalize_he.py -v`
Expected: PASS (6 passed)

- [ ] **Step 5: Commit**

```bash
git add benchmark/normalize_he.py benchmark/tests/test_normalize_he.py
git commit -m "feat(bench): Hebrew text normalization for WER"
```

---

## Task 3: WER/CER metrics (TDD)

**Files:**
- Create: `benchmark/metrics.py`
- Test: `benchmark/tests/test_metrics.py`

- [ ] **Step 1: Write the failing test**

```python
# benchmark/tests/test_metrics.py
from benchmark.metrics import score

def test_perfect_match_is_zero():
    r = score(["שלום עולם"], ["שָׁלוֹם, עולם!"])
    assert r["wer"] == 0.0
    assert r["cer"] == 0.0

def test_one_word_substitution():
    # 2 ref words, 1 wrong -> WER 0.5
    r = score(["שלום עולם"], ["שלום חבר"])
    assert abs(r["wer"] - 0.5) < 1e-9

def test_aggregates_over_corpus():
    r = score(["א ב", "ג ד"], ["א ב", "ג ה"])  # 4 words, 1 error
    assert abs(r["wer"] - 0.25) < 1e-9
```

- [ ] **Step 2: Run test to verify it fails**

Run: `benchmark/.venv/Scripts/python.exe -m pytest benchmark/tests/test_metrics.py -v`
Expected: FAIL with `ModuleNotFoundError: benchmark.metrics`

- [ ] **Step 3: Write minimal implementation**

```python
# benchmark/metrics.py
import jiwer
from benchmark.normalize_he import normalize_he

def score(refs, hyps):
    refs_n = [normalize_he(r) for r in refs]
    hyps_n = [normalize_he(h) for h in hyps]
    wer = jiwer.wer(refs_n, hyps_n)
    cer = jiwer.cer(refs_n, hyps_n)
    return {"wer": float(wer), "cer": float(cer)}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `benchmark/.venv/Scripts/python.exe -m pytest benchmark/tests/test_metrics.py -v`
Expected: PASS (3 passed)

- [ ] **Step 5: Commit**

```bash
git add benchmark/metrics.py benchmark/tests/test_metrics.py
git commit -m "feat(bench): WER/CER scoring over normalized Hebrew"
```

---

## Task 4: Eval dataset fetcher

Use Google FLEURS `he_il` test split (CC-BY, no gating) as the primary fixed eval set; take the first N clips deterministically. Each clip becomes a 16 kHz mono WAV + reference string.

**Files:**
- Create: `benchmark/datasets.py`

- [ ] **Step 1: Write `benchmark/datasets.py`**

```python
# benchmark/datasets.py
import os, soundfile as sf, librosa
from datasets import load_dataset

DATA_DIR = os.path.join(os.path.dirname(__file__), "data", "fleurs_he")

def prepare_fleurs(n=40):
    """Return list of (wav_path, reference). Deterministic first n test clips."""
    os.makedirs(DATA_DIR, exist_ok=True)
    ds = load_dataset("google/fleurs", "he_il", split="test")
    items = []
    for i in range(min(n, len(ds))):
        row = ds[i]
        wav_path = os.path.join(DATA_DIR, f"{i:04d}.wav")
        if not os.path.exists(wav_path):
            audio = row["audio"]
            y = audio["array"]
            sr = audio["sampling_rate"]
            if sr != 16000:
                y = librosa.resample(y, orig_sr=sr, target_sr=16000)
            sf.write(wav_path, y, 16000, subtype="PCM_16")
        items.append((wav_path, row["transcription"]))
    return items

if __name__ == "__main__":
    items = prepare_fleurs()
    print(f"prepared {len(items)} clips in {DATA_DIR}")
    print("sample ref:", items[0][1][:60])
```

- [ ] **Step 2: Run it to fetch the eval set**

Run: `benchmark/.venv/Scripts/python.exe -m benchmark.datasets`
Expected: `prepared 40 clips ...` and a Hebrew sample reference printed. (If `google/fleurs` errors, fall back to `mozilla-foundation/common_voice_17_0` config `he`, split `test`, fields `audio`/`sentence`; document the swap in the report.)

- [ ] **Step 3: Commit**

```bash
git add benchmark/datasets.py
git commit -m "feat(bench): fetch fixed Hebrew FLEURS eval set"
```

---

## Task 5: Model registry + faster-whisper runner

Defines every candidate and a uniform `transcribe(wav_path) -> (text, seconds)`. faster-whisper covers vanilla Whisper sizes and any CT2-format ivrit-ai model.

**Files:**
- Create: `benchmark/models.py`

- [ ] **Step 1: Write `benchmark/models.py`**

```python
# benchmark/models.py
import time

# id -> spec. backend: "fw" (faster-whisper) | "hf" (transformers)
REGISTRY = {
    "whisper-small":        {"backend": "fw", "name": "small"},
    "whisper-medium":       {"backend": "fw", "name": "medium"},
    "whisper-large-v3":     {"backend": "fw", "name": "large-v3"},
    "whisper-large-v3-turbo":{"backend": "fw", "name": "deepdml/faster-whisper-large-v3-turbo-ct2"},
    # ivrit-ai Hebrew fine-tunes (CT2 builds run directly in faster-whisper):
    "ivrit-large-v3-turbo": {"backend": "fw", "name": "ivrit-ai/whisper-large-v3-turbo-ct2"},
    "ivrit-large-v3":       {"backend": "fw", "name": "ivrit-ai/whisper-large-v3-ct2"},
}

def load_fw(name):
    from faster_whisper import WhisperModel
    return WhisperModel(name, device="cuda", compute_type="float16")

def transcribe_fw(model, wav_path):
    t0 = time.perf_counter()
    segments, _ = model.transcribe(wav_path, language="he", beam_size=5)
    text = "".join(s.text for s in segments)
    return text, time.perf_counter() - t0

def load_hf(name):
    from transformers import pipeline
    import torch
    return pipeline("automatic-speech-recognition", model=name,
                    device=0 if torch.cuda.is_available() else -1,
                    torch_dtype="auto",
                    generate_kwargs={"language": "he", "task": "transcribe"})

def transcribe_hf(pipe, wav_path):
    t0 = time.perf_counter()
    out = pipe(wav_path)
    return out["text"], time.perf_counter() - t0

def make_runner(model_id):
    spec = REGISTRY[model_id]
    if spec["backend"] == "fw":
        m = load_fw(spec["name"]); return lambda w: transcribe_fw(m, w)
    m = load_hf(spec["name"]); return lambda w: transcribe_hf(m, w)
```

- [ ] **Step 2: Smoke-test one model on one clip**

Run:
```bash
benchmark/.venv/Scripts/python.exe -c "from benchmark.models import make_runner; from benchmark.datasets import prepare_fleurs; r=make_runner('whisper-small'); w=prepare_fleurs(1)[0][0]; print(r(w))"
```
Expected: a `(hebrew_text, seconds)` tuple printed. (If an `ivrit-ai/*-ct2` repo id 404s, that model is not published in CT2 form — mark it `SKIP` in Task 6 and note it; the HF-format ivrit model is covered there.)

- [ ] **Step 3: Commit**

```bash
git add benchmark/models.py
git commit -m "feat(bench): model registry + faster-whisper/HF runners"
```

---

## Task 6: Add ivrit-ai HF-format fallback runner

The canonical ivrit-ai checkpoints are HF-transformers format. Add them via the `hf` backend so they are benchmarked even if no CT2 build exists.

**Files:**
- Modify: `benchmark/models.py` (extend `REGISTRY`)

- [ ] **Step 1: Add HF ivrit entries to `REGISTRY`** (append inside the dict)

```python
    "ivrit-hf-large-v3-turbo": {"backend": "hf", "name": "ivrit-ai/whisper-large-v3-turbo"},
    "ivrit-hf-large-v3":       {"backend": "hf", "name": "ivrit-ai/whisper-large-v3"},
```

- [ ] **Step 2: Smoke-test the HF ivrit runner on one clip**

Run:
```bash
benchmark/.venv/Scripts/python.exe -c "from benchmark.models import make_runner; from benchmark.datasets import prepare_fleurs; r=make_runner('ivrit-hf-large-v3-turbo'); w=prepare_fleurs(1)[0][0]; print(r(w))"
```
Expected: a `(hebrew_text, seconds)` tuple. (If a specific repo id 404s, replace with the current ivrit-ai turbo repo id found via `huggingface-hub` search and note it in the report.)

- [ ] **Step 3: Commit**

```bash
git add benchmark/models.py
git commit -m "feat(bench): add ivrit-ai HF-format models to registry"
```

---

## Task 7: Orchestrator + ranked report

Runs every model over the eval set, records WER/CER + mean RTF, writes JSON and a Markdown leaderboard. RTF = processing_seconds / audio_seconds (lower = faster; <1 is faster-than-realtime).

**Files:**
- Create: `benchmark/run_benchmark.py`

- [ ] **Step 1: Write `benchmark/run_benchmark.py`**

```python
# benchmark/run_benchmark.py
import os, json, traceback, soundfile as sf
from benchmark.datasets import prepare_fleurs
from benchmark.models import REGISTRY, make_runner
from benchmark.metrics import score

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")
DOC = os.path.join(os.path.dirname(__file__), "..", "docs", "benchmarks", "hebrew-asr-2026-06-29.md")

def audio_seconds(path):
    info = sf.info(path); return info.frames / info.samplerate

def run(n=40, only=None):
    os.makedirs(RESULTS_DIR, exist_ok=True)
    items = prepare_fleurs(n)
    refs = [r for _, r in items]
    total_audio = sum(audio_seconds(w) for w, _ in items)
    rows = []
    model_ids = only or list(REGISTRY.keys())
    for mid in model_ids:
        try:
            runner = make_runner(mid)
            hyps, proc = [], 0.0
            for wav, _ in items:
                text, secs = runner(wav); hyps.append(text); proc += secs
            s = score(refs, hyps)
            rows.append({"model": mid, "wer": s["wer"], "cer": s["cer"],
                         "rtf": proc / total_audio, "status": "ok"})
            print(f"{mid}: WER={s['wer']:.3f} CER={s['cer']:.3f} RTF={proc/total_audio:.2f}")
        except Exception as e:
            rows.append({"model": mid, "status": f"error: {e}"})
            print(f"{mid}: ERROR {e}"); traceback.print_exc()
    rows.sort(key=lambda r: (r.get("wer", 9), r.get("rtf", 9)))
    json.dump(rows, open(os.path.join(RESULTS_DIR, "results.json"), "w"),
              ensure_ascii=False, indent=2)
    write_doc(rows, n, total_audio)
    return rows

def write_doc(rows, n, total_audio):
    os.makedirs(os.path.dirname(DOC), exist_ok=True)
    lines = [f"# Hebrew ASR benchmark (2026-06-29)\n",
             f"Eval: FLEURS he_il test, first {n} clips ({total_audio:.0f}s audio). "
             f"WER/CER Hebrew-normalized. RTF on RTX 3060 (fp16).\n",
             "| Rank | Model | WER | CER | RTF | Status |",
             "|---|---|---|---|---|---|"]
    for i, r in enumerate(rows, 1):
        if r["status"] == "ok":
            lines.append(f"| {i} | {r['model']} | {r['wer']:.3f} | {r['cer']:.3f} | {r['rtf']:.2f} | ok |")
        else:
            lines.append(f"| {i} | {r['model']} | - | - | - | {r['status']} |")
    ok = [r for r in rows if r["status"] == "ok"]
    if ok:
        best = ok[0]
        lines += ["", f"**Winner: `{best['model']}`** "
                  f"(WER {best['wer']:.3f}, RTF {best['rtf']:.2f}). "
                  "Balanced target: lowest WER among models with RTF acceptable for "
                  "~1–2s latency on-device. Next step: convert to ggml (Task 8)."]
    open(DOC, "w", encoding="utf-8").write("\n".join(lines))

if __name__ == "__main__":
    run()
```

- [ ] **Step 2: Run the full benchmark**

Run: `benchmark/.venv/Scripts/python.exe -m benchmark.run_benchmark`
Expected: per-model `WER=.. CER=.. RTF=..` lines; `benchmark/results/results.json` and `docs/benchmarks/hebrew-asr-2026-06-29.md` written with a ranked table and a named winner.

- [ ] **Step 3: Commit**

```bash
git add benchmark/run_benchmark.py docs/benchmarks/hebrew-asr-2026-06-29.md benchmark/results/results.json
git commit -m "feat(bench): orchestrator + ranked Hebrew ASR leaderboard"
```

---

## Task 8: Convert the winner to ggml + quantize

Convert the chosen HF/PyTorch Whisper checkpoint to whisper.cpp ggml and quantize (q5_0 default; q8_0 if accuracy drop is unacceptable). Produce the exact file the Android app ships.

**Files:**
- Create: `benchmark/convert_to_ggml.py`
- Create: `models/` output dir (gitignored binaries)

- [ ] **Step 1: Clone + build whisper.cpp tooling**

Run:
```bash
git clone https://github.com/ggml-org/whisper.cpp ../whisper.cpp
cmake -S ../whisper.cpp -B ../whisper.cpp/build -DCMAKE_BUILD_TYPE=Release
cmake --build ../whisper.cpp/build --config Release -j
```
Expected: builds `quantize` and `whisper-cli` (or `main`) executables under `../whisper.cpp/build`.

- [ ] **Step 2: Write `benchmark/convert_to_ggml.py`**

```python
# benchmark/convert_to_ggml.py
import os, sys, subprocess, hashlib
from huggingface_hub import snapshot_download

WC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "whisper.cpp"))
OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "models"))

def convert(hf_repo: str, out_name: str, quant: str = "q5_0"):
    os.makedirs(OUT, exist_ok=True)
    local = snapshot_download(hf_repo)
    f32 = os.path.join(OUT, f"{out_name}-f32.bin")
    subprocess.check_call([sys.executable,
        os.path.join(WC, "models", "convert-h5-to-ggml.py"), local, local, OUT])
    # convert-h5-to-ggml writes ggml-model.bin into OUT; rename deterministically
    produced = os.path.join(OUT, "ggml-model.bin")
    os.replace(produced, f32)
    q = os.path.join(OUT, f"{out_name}-{quant}.bin")
    quant_exe = os.path.join(WC, "build", "bin", "quantize")
    if not os.path.exists(quant_exe):
        quant_exe = os.path.join(WC, "build", "quantize")
    subprocess.check_call([quant_exe, f32, q, quant])
    digest = hashlib.sha256(open(q, "rb").read()).hexdigest()
    open(q + ".sha256", "w").write(digest)
    print(f"wrote {q}\nsha256 {digest}\nsize {os.path.getsize(q)/1e6:.0f} MB")
    return q, digest

if __name__ == "__main__":
    # args: <hf_repo> <out_name> [quant]
    convert(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "q5_0")
```

- [ ] **Step 3: Convert the winning model** (substitute the winner from Task 7)

Run:
```bash
benchmark/.venv/Scripts/python.exe -m benchmark.convert_to_ggml ivrit-ai/whisper-large-v3-turbo dabber-he-turbo q5_0
```
Expected: `models/dabber-he-turbo-q5_0.bin` + `.sha256` written; size printed (~0.5–1.0 GB). (If the winner from Task 7 is a different repo, use that repo id and an appropriate `out_name`.)

- [ ] **Step 4: Commit (code + checksum only, not the binary)**

```bash
git add benchmark/convert_to_ggml.py models/dabber-he-turbo-q5_0.bin.sha256
git commit -m "feat(bench): ggml conversion+quantization of winning Hebrew model"
```

---

## Task 9: Validate the ggml model in whisper.cpp

Prove the shipped file actually transcribes Hebrew correctly, and record its on-CPU WER + speed (the real on-device proxy).

**Files:**
- Create: `benchmark/validate_ggml.py`

- [ ] **Step 1: Write `benchmark/validate_ggml.py`**

```python
# benchmark/validate_ggml.py
import os, sys, subprocess, glob
from benchmark.datasets import prepare_fleurs
from benchmark.metrics import score

WC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "whisper.cpp"))

def cli():
    for c in ["whisper-cli", "main"]:
        for p in [os.path.join(WC, "build", "bin", c), os.path.join(WC, "build", c)]:
            if os.path.exists(p) or os.path.exists(p + ".exe"):
                return p
    raise SystemExit("whisper.cpp cli not found")

def run(model_path, n=20):
    items = prepare_fleurs(n)
    exe = cli(); refs, hyps = [], []
    for wav, ref in items:
        out = subprocess.check_output(
            [exe, "-m", model_path, "-l", "he", "-otxt", "-nt", "-f", wav],
            stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
        hyps.append(out.strip()); refs.append(ref)
    s = score(refs, hyps)
    print(f"ggml WER={s['wer']:.3f} CER={s['cer']:.3f} on {n} clips")
    return s

if __name__ == "__main__":
    run(sys.argv[1])
```

- [ ] **Step 2: Validate the quantized model**

Run: `benchmark/.venv/Scripts/python.exe -m benchmark.validate_ggml models/dabber-he-turbo-q5_0.bin`
Expected: a printed WER/CER within a small delta of the Task 7 score for that model (confirms quantization didn't break Hebrew). If WER jumps significantly, re-run Task 8 Step 3 with `q8_0` and re-validate.

- [ ] **Step 3: Append the on-device proxy result to the report**

Append a line to `docs/benchmarks/hebrew-asr-2026-06-29.md`:
```text
On-device (whisper.cpp q5_0, CPU) WER=<value>, validated <date>. Shipped file: models/dabber-he-turbo-q5_0.bin (sha256 in .sha256).
```

- [ ] **Step 4: Commit**

```bash
git add benchmark/validate_ggml.py docs/benchmarks/hebrew-asr-2026-06-29.md
git commit -m "feat(bench): validate quantized ggml Hebrew model, record on-device WER"
```

---

## Self-Review

**Spec coverage (§4 of design):**
- Eval data → Task 4 (FLEURS he, fallback Common Voice). ✓
- Candidate models (vanilla + ivrit-ai) → Tasks 5–6. ✓ (NPU/ONNX spot-check is deferred to the Android plan, noted as a benchmark output there — acceptable since the chosen reliable path is whisper.cpp.)
- Metrics WER/CER/RTF → Tasks 3, 7. ✓
- Hard output: chosen model + ggml + checksum + comparison doc → Tasks 7, 8, 9. ✓
- Risk: best model not ggml-convertible → Task 8 ships best convertible; HF fallback in Task 6 ensures ivrit models are scored regardless of CT2 availability. ✓

**Placeholder scan:** No TBD/TODO; every code step has complete code. Model repo ids that may move have explicit "if 404, do X" verification fallbacks rather than silent gaps.

**Type consistency:** `normalize_he(str)->str`, `score(refs,hyps)->{"wer","cer"}`, runner `(wav)->(text,seconds)`, `convert(repo,name,quant)->(path,digest)` used consistently across tasks.

**Deliverable of this plan:** `models/<winner>-q5_0.bin` (+ sha256) and `docs/benchmarks/hebrew-asr-2026-06-29.md` — the exact inputs the Android plan (Plan 2) needs for its `WhisperEngine` task.
