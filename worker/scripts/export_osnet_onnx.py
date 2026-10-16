"""Export OSNet x1.0 (torchreid, trained on all of MSMT17) to ONNX as the back-view Re-ID embedder.

Runs only in the temporary torch environment that download_models.py creates; torch never enters the worker image.
The model code is torchreid's ``osnet.py`` (MIT) at a pinned commit; the checkpoint is the torchreid model zoo's
"osnet_x1_0, MSMT17 combineall" (weights trained on a research dataset: licence review required, PENDING P-09).
It is loaded with ``weights_only=True``, and the classifier layer (the training identities) is dropped.

The exported graph takes ``input`` float32 [N, 3, 256, 128], RGB, normalised with the ImageNet mean and standard
deviation, and returns ``features`` [N, 512] (not normalised; the worker L2-normalises).

Usage: python -I export_osnet_onnx.py --out <models dir> --work <empty work dir>
"""

from __future__ import annotations

import argparse
import importlib.util
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

COMMIT = "f8cd150fdf77e8d9e1ed143b7f308c2c609ded50"
MODEL_CODE = f"https://raw.githubusercontent.com/KaiyangZhou/deep-person-reid/{COMMIT}/torchreid/models/osnet.py"
# Google Drive file of "osnet_x1_0, MSMT17 (combineall=True)" in torchreid's docs/MODEL_ZOO.md at that commit.
CHECKPOINT = (
    "https://drive.usercontent.google.com/download?id=1IosIFlLiulGIjwW3H8uMRmx3MzPwf86x&export=download&confirm=t"
)
OUTPUT = "osnet_x1_0_msmt17.onnx"


def fetch(url: str, target: Path) -> Path:
    with urllib.request.urlopen(url, timeout=120) as response:  # noqa: S310 - pinned https URL
        target.write_bytes(response.read())
    return target


def export(out_dir: Path, work_dir: Path) -> None:
    code = fetch(MODEL_CODE, work_dir / "osnet.py")
    spec = importlib.util.spec_from_file_location("osnet", code)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load osnet.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    checkpoint = torch.load(
        fetch(CHECKPOINT, work_dir / "osnet_x1_0_msmt17.pth"), map_location="cpu", weights_only=True
    )
    state = checkpoint.get("state_dict", checkpoint)
    state = {
        key.removeprefix("module."): value
        for key, value in state.items()
        if not key.removeprefix("module.").startswith("classifier.")
    }
    model = module.osnet_x1_0(num_classes=1, pretrained=False, loss="softmax")
    missing, unexpected = model.load_state_dict(state, strict=False)
    if unexpected or any(not key.startswith("classifier.") for key in missing):
        raise RuntimeError(f"checkpoint does not fit OSNet x1.0: missing {missing}, unexpected {unexpected}")
    model.eval()

    sample = torch.randn(2, 3, 256, 128)
    target = out_dir / OUTPUT
    torch.onnx.export(
        model,
        (sample,),
        str(target),
        input_names=["input"],
        output_names=["features"],
        dynamic_axes={"input": {0: "batch"}, "features": {0: "batch"}},
        opset_version=18,
        external_data=False,
    )
    with torch.no_grad():
        expected = model(sample).numpy()
    actual = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"]).run(None, {"input": sample.numpy()})[
        0
    ]
    difference = float(np.max(np.abs(expected - actual)))
    if difference > 1e-3:
        raise RuntimeError(f"{OUTPUT}: ONNX differs from torch by {difference}")
    print(f"exported {OUTPUT} (max difference to torch {difference:.2e})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    export(args.out, args.work)


if __name__ == "__main__":
    main()
