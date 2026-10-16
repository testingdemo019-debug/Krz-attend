# syntax=docker/dockerfile:1
# KRZ Attend vision worker image (P8). Build context = the project root:
#   docker build -f worker/Dockerfile -t krz-attend-worker:<VERSION> .                                  GPU
#   docker build -f worker/Dockerfile --build-arg RUNTIME=cpu -t krz-attend-worker:<VERSION>-cpu .      CPU
# GPU: onnxruntime-gpu with CUDA 13 and cuDNN 9 as NVIDIA's pip wheels, ONNX Runtime's documented pip install
# (DECISIONS D-071); the host needs NVIDIA driver >= 580 and the NVIDIA Container Toolkit (or Docker Desktop with
# WSL2 GPU support). CPU: onnxruntime, for development machines without a GPU (deploy/docker-compose.cpu.yml).
# The models are not in the image: data/models is mounted read-only at /models and checked against models.json at
# start. Tests and lint run on the host before the build (worker/tests, ruff, mypy, pip-audit).

ARG PYTHON_IMAGE=python:3.11.17-slim-trixie

FROM ${PYTHON_IMAGE} AS build
ARG RUNTIME=gpu
COPY worker/requirements.lock worker/requirements-cpu.lock /tmp/
RUN --mount=type=cache,target=/root/.cache/pip \
    set -eu; \
    case "$RUNTIME" in \
      gpu) lock=/tmp/requirements.lock ;; \
      cpu) lock=/tmp/requirements-cpu.lock ;; \
      *) echo "RUNTIME must be gpu or cpu, not '$RUNTIME'" >&2; exit 1 ;; \
    esac; \
    python -m venv /opt/venv; \
    /opt/venv/bin/pip install --require-hashes --no-deps --only-binary=:all: -r "$lock"; \
    /opt/venv/bin/pip uninstall --yes pip setuptools
COPY worker/src/ /app/src/
RUN /opt/venv/bin/python -m compileall -q /app/src

FROM ${PYTHON_IMAGE}
# Nothing installs packages at run time: pip, setuptools and wheel leave the image.
RUN python -m pip uninstall --yes --quiet pip setuptools wheel \
    && groupadd --system --gid 10001 krz \
    && useradd --system --uid 10001 --gid krz --no-create-home --shell /usr/sbin/nologin krz
COPY --from=build /opt/venv /opt/venv
COPY --from=build /app/src /app/src
COPY worker/models/models.json /app/models/models.json
WORKDIR /app
# The root file system is read-only in Compose; matplotlib (through supervision) keeps its cache in the /tmp tmpfs.
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONPATH=/app/src \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MPLCONFIGDIR=/tmp/matplotlib
USER 10001:10001
# 8090: the internal API (backend only; never published in production).
EXPOSE 8090
# Healthy = models loaded and the first configuration read (/health is not "starting"); a camera being offline is
# "degraded", which is still healthy (the pipeline reconnects by itself).
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=4 \
    CMD ["python", "-c", "import json, os, sys, urllib.request; port = os.environ.get('WORKER_API_PORT', '8090'); body = json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=4)); sys.exit(body['status'] == 'starting')"]
ENTRYPOINT ["python", "-m", "krz_worker"]
