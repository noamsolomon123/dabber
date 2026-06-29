import os
import subprocess
import sys

from benchmark.datasets import prepare_fleurs
from benchmark.metrics import score

WC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "whisper.cpp"))


def cli():
    for c in ("whisper-cli", "main"):
        for p in (
            os.path.join(WC, "build", "bin", c),
            os.path.join(WC, "build", "bin", c + ".exe"),
            os.path.join(WC, "build", c),
            os.path.join(WC, "build", c + ".exe"),
        ):
            if os.path.exists(p):
                return p
    raise SystemExit("whisper.cpp cli not found")


def run(model_path, n=20):
    items = prepare_fleurs(n)
    exe = cli()
    refs, hyps = [], []
    for wav, ref in items:
        out = subprocess.check_output(
            [exe, "-m", model_path, "-l", "he", "-otxt", "-nt", "-f", wav],
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
        )
        hyps.append(out.strip())
        refs.append(ref)
    s = score(refs, hyps)
    print(f"ggml WER={s['wer']:.3f} CER={s['cer']:.3f} on {n} clips")
    return s


if __name__ == "__main__":
    run(sys.argv[1])
