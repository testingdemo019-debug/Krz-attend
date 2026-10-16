"""Export the two Silent-Face anti-spoofing models (MiniFASNetV2 at scale 2.7, MiniFASNetV1SE at 4.0; 80x80) to ONNX.

Runs only in the temporary torch environment that download_models.py creates; torch never enters the worker image.
Sources are pinned to one commit of github.com/minivision-ai/Silent-Face-Anti-Spoofing (Apache-2.0). The weights are
loaded with ``weights_only=True``, so the checkpoint cannot run code.

The exported graph takes ``input`` float32 [N, 3, 80, 80], BGR, values 0-255 (the repository's ToTensor does not divide
by 255), and returns ``logits`` [N, 3]; class 1 is a real face. The worker applies the softmax and fuses both models.

Usage: python -I export_antispoof_onnx.py --out <models dir> --work <empty work dir>
"""

from __future__ import annotations

import argparse
import importlib.util
import urllib.request
from collections import OrderedDict
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

COMMIT = "b6d5f04ad78778917853b25c778acef6d5626d15"
BASE = f"https://raw.githubusercontent.com/minivision-ai/Silent-Face-Anti-Spoofing/{COMMIT}"
MODEL_CODE = "src/model_lib/MiniFASNet.py"
# (output file, constructor in MiniFASNet.py, weights in the repository)
MODELS = [
    ("minifasnet_v2_2.7_80x80.onnx", "MiniFASNetV2", "resources/anti_spoof_models/2.7_80x80_MiniFASNetV2.pth"),
    ("minifasnet_v1se_4.0_80x80.onnx", "MiniFASNetV1SE", "resources/anti_spoof_models/4_0_0_80x80_MiniFASNetV1SE.pth"),
]
INPUT = 80
# The repository's get_kernel(80, 80): ((80 + 15) // 16, (80 + 15) // 16).
CONV6_KERNEL = (5, 5)


def fetch(relative: str, target: Path) -> Path:
    with urllib.request.urlopen(f"{BASE}/{relative}", timeout=60) as response:  # noqa: S310 - pinned https URL
        target.write_bytes(response.read())
    return target


def export(out_dir: Path, work_dir: Path) -> None:
    code = fetch(MODEL_CODE, work_dir / "minifasnet.py")
    spec = importlib.util.spec_from_file_location("minifasnet", code)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load MiniFASNet.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    for file_name, constructor, weights in MODELS:
        checkpoint = fetch(weights, work_dir / Path(weights).name)
        state = torch.load(checkpoint, map_location="cpu", weights_only=True)
        state = OrderedDict((key.removeprefix("module."), value) for key, value in state.items())
        model = getattr(module, constructor)(conv6_kernel=CONV6_KERNEL)
        model.load_state_dict(state)
        model.eval()
        sample = torch.rand(2, 3, INPUT, INPUT) * 255
        target = out_dir / file_name
        torch.onnx.export(
            model,
            (sample,),
            str(target),
            input_names=["input"],
            output_names=["logits"],
            dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
            opset_version=18,
            external_data=False,
        )
        with torch.no_grad():
            expected = model(sample).numpy()
        actual = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"]).run(
            None, {"input": sample.numpy()}
        )[0]
        difference = float(np.max(np.abs(expected - actual)))
        if difference > 1e-3:
            raise RuntimeError(f"{file_name}: ONNX differs from torch by {difference}")
        print(f"exported {file_name} (max difference to torch {difference:.2e})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    export(args.out, args.work)


if __name__ == "__main__":
    main()
