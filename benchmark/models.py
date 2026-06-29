import time

# id -> spec. backend: "fw" (faster-whisper) | "hf" (transformers)
REGISTRY = {
    "whisper-small":          {"backend": "fw", "name": "small"},
    "whisper-medium":         {"backend": "fw", "name": "medium"},
    "whisper-large-v3":       {"backend": "fw", "name": "large-v3"},
    "whisper-large-v3-turbo": {"backend": "fw", "name": "deepdml/faster-whisper-large-v3-turbo-ct2"},
    # ivrit-ai Hebrew fine-tunes (CT2 builds run directly in faster-whisper):
    "ivrit-large-v3-turbo":   {"backend": "fw", "name": "ivrit-ai/whisper-large-v3-turbo-ct2"},
    "ivrit-large-v3":         {"backend": "fw", "name": "ivrit-ai/whisper-large-v3-ct2"},
    # ivrit-ai HF-format fallbacks (benchmarked even if no CT2 build exists):
    "ivrit-hf-large-v3-turbo": {"backend": "hf", "name": "ivrit-ai/whisper-large-v3-turbo"},
    "ivrit-hf-large-v3":       {"backend": "hf", "name": "ivrit-ai/whisper-large-v3"},
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
    import torch
    from transformers import pipeline
    return pipeline(
        "automatic-speech-recognition",
        model=name,
        device=0 if torch.cuda.is_available() else -1,
        torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
        chunk_length_s=30,
        generate_kwargs={"language": "he", "task": "transcribe"},
    )


def transcribe_hf(pipe, wav_path):
    t0 = time.perf_counter()
    out = pipe(wav_path)
    return out["text"], time.perf_counter() - t0


def make_runner(model_id):
    spec = REGISTRY[model_id]
    if spec["backend"] == "fw":
        m = load_fw(spec["name"])
        return lambda w: transcribe_fw(m, w)
    m = load_hf(spec["name"])
    return lambda w: transcribe_hf(m, w)
