"""Download and export the worker's models into MODELS_DIR, check or record their SHA-256, and render MODELS.md.

1. Every model with a ``url`` in worker/models/models.json is downloaded (skipped when the file already matches).
2. The exported models (MiniFASNet) are built in a temporary virtual environment with torch, which is deleted
   afterwards: torch never reaches the worker image.
3. A downloaded file must match the ``sha256`` in models.json. When models.json has none yet (first download or
   export) it is recorded, but only with ``--record``; a later mismatch stops the script. An export is not
   byte-identical between machines: a machine's own export is kept in MODELS_DIR/local-exports.json, never in
   models.json.
4. A model file that is no longer in models.json is removed from MODELS_DIR.
5. worker/models/MODELS.md is rendered from models.json with each file's licence, source, SHA-256 and input/output
   shapes. models.json itself is only rewritten when something in it changed (it is in Git; a pull must not meet a
   local change).

start-all.ps1 runs this script after a pull. NVIDIA's Re-ID model comes from NVIDIA NGC under its Deep Learning Models
License Agreement (see models.json).

Usage (from worker/, with the worker's virtual environment):
    python scripts/download_models.py [--models-dir ../data/models] [--record] [--skip-export]
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Any

import onnxruntime as ort

from krz_worker.models.registry import LOCAL_EXPORTS, accepted_sha256

WORKER = Path(__file__).resolve().parent.parent
CATALOG = WORKER / "models" / "models.json"
MODELS_MD = WORKER / "models" / "MODELS.md"
EXPORT_REQUIREMENTS = ["torch==2.14.1", "onnx==1.23.2", "onnxscript==0.7.2", "onnxruntime==1.31.0", "numpy==2.4.6"]
TORCH_INDEX = "https://download.pytorch.org/whl/cpu"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def accept(entry: dict[str, Any], path: Path, record: bool) -> None:
    """The file's hash must equal the recorded one; with --record a missing one is recorded."""
    actual = sha256(path)
    expected = entry.get("sha256")
    if expected is None:
        if not record:
            raise SystemExit(f"{entry['file']}: no SHA-256 recorded yet ({actual}); run again with --record")
        entry["sha256"] = actual
        print(f"  recorded sha256 {actual}")
    elif actual != expected:
        raise SystemExit(f"{entry['file']}: SHA-256 {actual} differs from the recorded {expected}")


def matches(entry: dict[str, Any], path: Path) -> bool:
    return path.is_file() and sha256(path) in accepted_sha256(entry, path.parent)


def remember_export(entry: dict[str, Any], path: Path) -> None:
    """This machine just exported the model itself: its checksum is accepted here, without touching models.json."""
    local = path.parent / LOCAL_EXPORTS
    known = json.loads(local.read_text(encoding="utf-8")) if local.is_file() else {}
    known[entry["file"]] = sha256(path)
    local.write_text(json.dumps(known, indent=2) + "\n", encoding="utf-8")
    print(f"  this machine's own export: its checksum is kept in {local.name}")


def download(entry: dict[str, Any], models_dir: Path, record: bool) -> None:
    target = models_dir / entry["file"]
    print(f"{entry['file']}: {entry['licence']}")
    if matches(entry, target):
        print("  present, SHA-256 matches")
        return
    part = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(entry["url"], timeout=300) as response, part.open("wb") as out:  # noqa: S310
        shutil.copyfileobj(response, out)
    try:
        accept(entry, part, record)
    except SystemExit:
        part.unlink(missing_ok=True)
        raise
    part.replace(target)
    print("  downloaded")


def export(entries: list[dict[str, Any]], models_dir: Path, record: bool) -> None:
    pending = [e for e in entries if not matches(e, models_dir / e["file"])]
    for entry in entries:
        if entry not in pending:
            print(f"{entry['file']}: {entry['licence']}\n  present, SHA-256 matches")
    if not pending:
        return
    scripts = sorted({e["export"] for e in pending})
    with tempfile.TemporaryDirectory(prefix="krz-export-") as temp:
        venv = Path(temp) / "venv"
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
        python = venv / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        print("installing torch into a temporary environment (removed afterwards)")
        subprocess.run(
            [str(python), "-m", "pip", "install", "-q", "--extra-index-url", TORCH_INDEX, *EXPORT_REQUIREMENTS],
            check=True,
        )
        for script in scripts:
            work = Path(temp) / f"work-{script}"
            work.mkdir()
            subprocess.run(
                # -I isolates from the environment; -X utf8 because torch's exporter prints emoji (Windows consoles).
                [
                    str(python),
                    "-I",
                    "-X",
                    "utf8",
                    str(WORKER / "scripts" / script),
                    "--out",
                    str(models_dir),
                    "--work",
                    str(work),
                ],
                check=True,
            )
    for entry in pending:
        print(f"{entry['file']}: {entry['licence']}")
        path = models_dir / entry["file"]
        if entry.get("sha256") is None:
            accept(entry, path, record)
        elif not matches(entry, path):
            remember_export(entry, path)


def remove_retired(entries: list[dict[str, Any]], models_dir: Path) -> None:
    """A model that left the catalogue does not stay on the machine: its licence may be why it left."""
    known = {entry["file"] for entry in entries}
    for file in models_dir.glob("*.onnx"):
        if file.name not in known:
            file.unlink()
            print(f"{file.name}: no longer in the catalogue, removed")


def describe(path: Path) -> dict[str, list[str]]:
    """Input and output names, shapes and types as ONNX Runtime reports them."""
    options = ort.SessionOptions()
    options.log_severity_level = 3  # errors only: SFace lists its weights as graph inputs, a harmless warning each
    session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])

    def text(items: Any) -> list[str]:
        return [f"{i.name} {list(i.shape)} {i.type}" for i in items]

    return {"inputs": text(session.get_inputs()), "outputs": text(session.get_outputs())}


def render(catalog: dict[str, Any]) -> str:
    lines = [
        "# MODELS — what the worker runs",
        "",
        "Generated by `worker/scripts/download_models.py` from `worker/models/models.json` "
        f"on {dt.date.today().isoformat()}. Do not edit by hand.",
        "The `.onnx` files live in `MODELS_DIR` (`data/models`, mounted read-only at `/models`), never in the "
        "repository or the image. The worker verifies every SHA-256 at start-up and refuses to run on a mismatch.",
        "Only licences that allow commercial use: permissive ones, and NVIDIA's for its Re-ID model (on NVIDIA GPUs, "
        "through TensorRT, never handed out on its own). No YOLOv8/YOLO11 (AGPL), no InsightFace/ArcFace/SCRFD weights "
        "(non-commercial), no weights trained on research-only data such as MSMT17.",
        "",
    ]
    for entry in catalog["models"]:
        lines += [
            f"## {entry['name']} {entry['version']} — {entry['role'].replace('_', ' ')}",
            f"- File: `{entry['file']}`",
            f"- Licence: {entry['licence']}",
            f"- Source: {entry['source']}",
            f"- Obtained by: {'download of ' + entry['url'] if 'url' in entry else 'scripts/' + entry['export']}",
            f"- SHA-256: `{entry.get('sha256')}` ({entry['sha256_origin']})",
        ]
        for key in ("inputs", "outputs"):
            lines += [f"- {key.capitalize()}: " + "; ".join(f"`{item}`" for item in entry.get(key, []))]
        lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    default_dir = os.environ.get("MODELS_DIR") or str(WORKER.parent / "data" / "models")
    parser.add_argument("--models-dir", type=Path, default=Path(default_dir))
    parser.add_argument("--record", action="store_true", help="record the SHA-256 of models that have none yet")
    parser.add_argument("--skip-export", action="store_true", help="only the downloaded models")
    args = parser.parse_args()
    models_dir: Path = args.models_dir.resolve()
    models_dir.mkdir(parents=True, exist_ok=True)

    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    entries: list[dict[str, Any]] = catalog["models"]
    for entry in (e for e in entries if "url" in e):
        download(entry, models_dir, args.record)
    if not args.skip_export:
        export([e for e in entries if "export" in e], models_dir, args.record)
    remove_retired(entries, models_dir)
    for entry in entries:
        path = models_dir / entry["file"]
        if path.is_file() and "inputs" not in entry:
            entry.update(describe(path))

    text = json.dumps(catalog, indent=2, ensure_ascii=False) + "\n"
    if CATALOG.read_text(encoding="utf-8") != text:
        CATALOG.write_text(text, encoding="utf-8", newline="\n")
    MODELS_MD.write_text(render(catalog), encoding="utf-8")
    print(f"models in {models_dir}; wrote {CATALOG.name} and {MODELS_MD.name}")


if __name__ == "__main__":
    main()
