# Hebrew ASR benchmark (2026-06-29)

Eval: FLEURS he_il test, first 40 clips (375s audio). WER/CER Hebrew-normalized. RTF on RTX 3060 (fp16).

| Rank | Model | WER | CER | RTF | Status |
|---|---|---|---|---|---|
| 1 | ivrit-large-v3-turbo | 0.221 | 0.115 | 0.09 | ok |
| 2 | ivrit-large-v3 | 0.227 | 0.115 | 0.31 | ok |
| 3 | whisper-large-v3 | 0.291 | 0.136 | 0.28 | ok |
| 4 | whisper-large-v3-turbo | 0.327 | 0.140 | 0.11 | ok |
| 5 | whisper-medium | 0.357 | 0.153 | 0.22 | ok |
| 6 | whisper-small | 0.506 | 0.205 | 0.13 | ok |

**Winner: `ivrit-large-v3-turbo`** (WER 0.221, RTF 0.09). Balanced target: lowest WER among models with RTF acceptable for ~1-2s latency on-device. Next step: convert to ggml (Task 8).