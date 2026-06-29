import os

import librosa
import soundfile as sf
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
