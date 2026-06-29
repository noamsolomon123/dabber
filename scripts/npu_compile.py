"""Compile the ivrit-ai whisper-large-v3-turbo to a Hexagon-NPU (QNN) binary via Qualcomm AI Hub.

Overrides the AI Hub recipe's weights to the ivrit-ai Hebrew fine-tune (same architecture),
then exports a precompiled_qnn_onnx asset (ONNX wrapper + embedded QNN context binary) for the
Snapdragon 8 Elite Gen 5 (OnePlus 15). Output -> out-npu/.

Usage:  python scripts/npu_compile.py "<exact AI Hub device name>"
"""
import sys

# 1) point the AI Hub Whisper recipe at the ivrit-ai Hebrew weights (transformers checkpoint)
from qai_hub_models.models.whisper_large_v3_turbo import model as M
M.WHISPER_VERSION = "ivrit-ai/whisper-large-v3-turbo"

from qai_hub_models.models.whisper_large_v3_turbo import export  # noqa: E402

# The pinned lib hardcodes QAIRT 2.42 (which AI Hub dropped) and raises before our
# --qairt_version flag applies; force a supported version.
from qai_hub_models.models.common import InferenceEngine, QAIRTVersion  # noqa: E402
try:
    _ok = QAIRTVersion("default")
except Exception:
    _ok = QAIRTVersion("2.45")
InferenceEngine.default_qairt_version = property(lambda self: _ok)

device = sys.argv[1] if len(sys.argv) > 1 else "Snapdragon 8 Elite Gen 5 QRD"

sys.argv = [
    "export",
    "--target-runtime", "precompiled_qnn_onnx",
    "--device", device,
    "--precision", "float",
    "--output-dir", "out-npu",
    "--compile-options=--qairt_version=default",
    "--skip-profiling",
]
export.main()
