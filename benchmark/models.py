import time

# Every candidate runs through HF Transformers on the GPU. torch's CUDA path is reliable
# here, unlike faster-whisper/CTranslate2 (which hit a cuDNN symbol mismatch on this box).
# id -> HuggingFace repo. Vanilla Whisper sizes vs ivrit-ai Hebrew fine-tunes.
REGISTRY = {
    "whisper-small":          "openai/whisper-small",
    "whisper-medium":         "openai/whisper-medium",
    "whisper-large-v3":       "openai/whisper-large-v3",
    "whisper-large-v3-turbo": "openai/whisper-large-v3-turbo",
    "ivrit-large-v3-turbo":   "ivrit-ai/whisper-large-v3-turbo",
    "ivrit-large-v3":         "ivrit-ai/whisper-large-v3",
}


def load_hf(name):
    import torch
    from transformers import pipeline
    return pipeline(
        "automatic-speech-recognition",
        model=name,
        device=0 if torch.cuda.is_available() else -1,
        torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
        chunk_length_s=30,
    )


def transcribe_hf(pipe, wav_path):
    t0 = time.perf_counter()
    out = pipe(wav_path, generate_kwargs={"language": "he", "task": "transcribe"})
    return out["text"], time.perf_counter() - t0


def make_runner(model_id):
    pipe = load_hf(REGISTRY[model_id])
    return lambda w: transcribe_hf(pipe, w)
