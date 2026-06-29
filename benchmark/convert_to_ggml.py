import hashlib
import os
import subprocess
import sys

from huggingface_hub import snapshot_download

WC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "whisper.cpp"))
OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "models"))


def _quantize_exe():
    for p in (
        os.path.join(WC, "build", "bin", "quantize"),
        os.path.join(WC, "build", "bin", "quantize.exe"),
        os.path.join(WC, "build", "quantize"),
        os.path.join(WC, "build", "quantize.exe"),
    ):
        if os.path.exists(p):
            return p
    raise SystemExit("whisper.cpp quantize binary not found; build whisper.cpp first")


def convert(hf_repo: str, out_name: str, quant: str = "q5_0"):
    os.makedirs(OUT, exist_ok=True)
    local = snapshot_download(hf_repo)
    f32 = os.path.join(OUT, f"{out_name}-f32.bin")
    subprocess.check_call(
        [
            sys.executable,
            os.path.join(WC, "models", "convert-h5-to-ggml.py"),
            local,
            local,
            OUT,
        ]
    )
    produced = os.path.join(OUT, "ggml-model.bin")
    os.replace(produced, f32)
    q = os.path.join(OUT, f"{out_name}-{quant}.bin")
    subprocess.check_call([_quantize_exe(), f32, q, quant])
    digest = hashlib.sha256(open(q, "rb").read()).hexdigest()
    open(q + ".sha256", "w").write(digest)
    print(f"wrote {q}\nsha256 {digest}\nsize {os.path.getsize(q)/1e6:.0f} MB")
    return q, digest


if __name__ == "__main__":
    # args: <hf_repo> <out_name> [quant]
    convert(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "q5_0")
