import json
import os
import sys
import traceback


def _add_cuda_dll_dirs():
    """Ensure CTranslate2 (faster-whisper) can find cuDNN/cuBLAS shipped via pip on Windows."""
    if sys.platform != "win32":
        return
    scripts = os.path.dirname(sys.executable)
    site = os.path.join(os.path.dirname(scripts), "Lib", "site-packages")
    for sub in ("nvidia/cudnn/bin", "nvidia/cublas/bin", "torch/lib"):
        d = os.path.join(site, *sub.split("/"))
        if os.path.isdir(d):
            os.add_dll_directory(d)


_add_cuda_dll_dirs()

import soundfile as sf

from benchmark.datasets import prepare_fleurs
from benchmark.metrics import score
from benchmark.models import REGISTRY, make_runner

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")
DOC = os.path.join(
    os.path.dirname(__file__), "..", "docs", "benchmarks", "hebrew-asr-2026-06-29.md"
)


def audio_seconds(path):
    info = sf.info(path)
    return info.frames / info.samplerate


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
                text, secs = runner(wav)
                hyps.append(text)
                proc += secs
            s = score(refs, hyps)
            rows.append(
                {
                    "model": mid,
                    "wer": s["wer"],
                    "cer": s["cer"],
                    "rtf": proc / total_audio,
                    "status": "ok",
                }
            )
            print(f"{mid}: WER={s['wer']:.3f} CER={s['cer']:.3f} RTF={proc/total_audio:.2f}")
        except Exception as e:  # noqa: BLE001 - record and continue across models
            rows.append({"model": mid, "status": f"error: {e}"})
            print(f"{mid}: ERROR {e}")
            traceback.print_exc()
        finally:
            try:
                del runner
            except NameError:
                pass
            import gc

            import torch

            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
    rows.sort(key=lambda r: (r.get("wer", 9), r.get("rtf", 9)))
    json.dump(
        rows,
        open(os.path.join(RESULTS_DIR, "results.json"), "w", encoding="utf-8"),
        ensure_ascii=False,
        indent=2,
    )
    write_doc(rows, n, total_audio)
    return rows


def write_doc(rows, n, total_audio):
    os.makedirs(os.path.dirname(DOC), exist_ok=True)
    lines = [
        "# Hebrew ASR benchmark (2026-06-29)\n",
        f"Eval: FLEURS he_il test, first {n} clips ({total_audio:.0f}s audio). "
        "WER/CER Hebrew-normalized. RTF on RTX 3060 (fp16).\n",
        "| Rank | Model | WER | CER | RTF | Status |",
        "|---|---|---|---|---|---|",
    ]
    for i, r in enumerate(rows, 1):
        if r["status"] == "ok":
            lines.append(
                f"| {i} | {r['model']} | {r['wer']:.3f} | {r['cer']:.3f} | {r['rtf']:.2f} | ok |"
            )
        else:
            lines.append(f"| {i} | {r['model']} | - | - | - | {r['status']} |")
    ok = [r for r in rows if r["status"] == "ok"]
    if ok:
        best = ok[0]
        lines += [
            "",
            f"**Winner: `{best['model']}`** "
            f"(WER {best['wer']:.3f}, RTF {best['rtf']:.2f}). "
            "Balanced target: lowest WER among models with RTF acceptable for "
            "~1-2s latency on-device. Next step: convert to ggml (Task 8).",
        ]
    open(DOC, "w", encoding="utf-8").write("\n".join(lines))


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=40)
    ap.add_argument("--models", default=None, help="comma-separated model ids")
    args = ap.parse_args()
    only = args.models.split(",") if args.models else None
    run(n=args.n, only=only)
