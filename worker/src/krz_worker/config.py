"""Typed settings from the environment (``deploy/.env`` through Compose). Camera, recognition and gallery data come from
the database views instead (db.py).

``--host-dev`` runs the worker on a development machine without Docker: it reads ``deploy/.env`` itself and points at
localhost for PostgreSQL and Redis (published on 127.0.0.1 by docker-compose.dev.yml) and at ``data/`` for files, like
the backend's ``dev`` profile.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from dotenv import dotenv_values
from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

WORKER_DIR = Path(__file__).resolve().parents[2]
DEPLOY_DIR = WORKER_DIR.parent / "deploy"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore", populate_by_name=False, frozen=True)

    app_version: str = Field("0.0.0", validation_alias="APP_VERSION")
    worker_id: str = Field("worker-1", validation_alias="WORKER_ID", pattern=r"^[A-Za-z0-9_-]{1,32}$")
    execution_provider: Literal["tensorrt", "cuda", "cpu"] = Field("cuda", validation_alias="WORKER_EXECUTION_PROVIDER")
    gpu_device: int = Field(0, validation_alias="WORKER_GPU_DEVICE", ge=0)
    max_fps: float = Field(12.0, validation_alias="WORKER_MAX_FPS", gt=0, le=30)
    api_port: int = Field(8090, validation_alias="WORKER_API_PORT", ge=1, le=65535)
    api_token: SecretStr = Field(validation_alias="WORKER_API_TOKEN", min_length=48)

    data_dir: Path = Field(Path("/data"), validation_alias="DATA_DIR")
    models_dir: Path = Field(Path("/models"), validation_alias="MODELS_DIR")
    models_catalog: Path = Field(Path("/app/models/models.json"), validation_alias="MODELS_CATALOG")
    trt_cache_dir: Path = Field(Path("/data/trt_cache"), validation_alias="TRT_CACHE_DIR")
    discovery_subnet: str = Field("192.168.1.0/24", validation_alias="CAMERA_DISCOVERY_SUBNET")

    db_host: str = Field("host.docker.internal", validation_alias="DB_HOST")
    db_port: int = Field(5432, validation_alias="DB_PORT")
    db_name: str = Field("krz_attend", validation_alias="DB_NAME")
    db_user: str = Field("krz_worker", validation_alias="DB_WORKER_USER")
    db_password: SecretStr = Field(validation_alias="DB_WORKER_PASSWORD")

    redis_host: str = Field("redis", validation_alias="REDIS_HOST")
    redis_port: int = Field(6379, validation_alias="REDIS_PORT")
    redis_user: str = Field("krz_worker", validation_alias="REDIS_WORKER_USER")
    redis_password: SecretStr = Field(validation_alias="REDIS_WORKER_PASSWORD")

    @property
    def crops_dir(self) -> Path:
        return self.data_dir / "crops"

    @property
    def spool_dir(self) -> Path:
        return self.data_dir / "spool"

    @property
    def log_dir(self) -> Path:
        return self.data_dir / "logs" / "worker"


@dataclass(frozen=True)
class Loaded:
    settings: Settings
    #: The environment the settings came from; the key ring reads ENCRYPTION_KEY_* from it.
    env: Mapping[str, str]


def load(host_dev: bool = False) -> Loaded:
    if not host_dev:
        return Loaded(Settings(), dict(os.environ))
    env_file = DEPLOY_DIR / ".env"
    from_file = {key: value for key, value in dotenv_values(env_file).items() if value is not None}
    env = from_file | dict(os.environ)
    data_dir = (DEPLOY_DIR / env.get("DATA_DIR", "../data")).resolve()
    settings = Settings(_env_file=env_file).model_copy(
        update={
            "db_host": "localhost",
            "redis_host": "localhost",
            "data_dir": data_dir,
            "models_dir": data_dir / "models",
            "trt_cache_dir": data_dir / "trt_cache",
            "models_catalog": WORKER_DIR / "models" / "models.json",
        }
    )
    return Loaded(settings, env)
