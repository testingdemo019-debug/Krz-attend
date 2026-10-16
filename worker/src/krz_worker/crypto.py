"""AES-256-GCM in the backend's byte format (security §6; verified by docs/test-vectors/aes-gcm.json):

    "v1" (2 ASCII bytes) | key id length (1 byte) | key id (ASCII) | nonce (12 bytes) | ciphertext + 16-byte tag

The header ("v1", length, key id) is the additional authenticated data, so it cannot be altered. Keys come from
``ENCRYPTION_KEY_<ID>`` (base64 of 32 bytes); ``ENCRYPTION_ACTIVE_KEY_ID`` names the one new values are sealed with.
Decrypted embeddings and images exist in memory only.
"""

from __future__ import annotations

import base64
import binascii
import os
import re
import secrets
import tempfile
from collections.abc import Mapping
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VERSION = b"v1"
NONCE_LENGTH = 12
TAG_LENGTH = 16
KEY_PREFIX = "ENCRYPTION_KEY_"
_KEY_ID = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
FILE_MODE = 0o640


class CryptoError(Exception):
    """A value that cannot be decrypted: wrong format, unknown key or failed authentication."""


class KeyRing:
    def __init__(self, keys: Mapping[str, bytes], active_key_id: str) -> None:
        for key_id, key in keys.items():
            if not _KEY_ID.match(key_id) or len(key) != 32:
                raise ValueError(f"{KEY_PREFIX}{key_id} must be base64 of 32 bytes with a key id of [A-Za-z0-9_-]")
        if active_key_id not in keys:
            raise ValueError(
                f"ENCRYPTION_ACTIVE_KEY_ID is '{active_key_id}' but {KEY_PREFIX}{active_key_id} is not set "
                "(run deploy/scripts/init-env)"
            )
        self._keys = {key_id: AESGCM(key) for key_id, key in keys.items()}
        self.active_key_id = active_key_id

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> KeyRing:
        keys: dict[str, bytes] = {}
        for name, value in env.items():
            if name.startswith(KEY_PREFIX) and value:
                try:
                    keys[name[len(KEY_PREFIX) :]] = base64.b64decode(value, validate=True)
                except binascii.Error as error:
                    raise ValueError(f"{name} is not base64") from error
        return cls(keys, env.get("ENCRYPTION_ACTIVE_KEY_ID", "k1"))

    def encrypt(self, plaintext: bytes) -> bytes:
        return self._seal(plaintext, self.active_key_id, secrets.token_bytes(NONCE_LENGTH))

    def _seal(self, plaintext: bytes, key_id: str, nonce: bytes) -> bytes:
        header = VERSION + bytes([len(key_id)]) + key_id.encode("ascii")
        return header + nonce + self._keys[key_id].encrypt(nonce, plaintext, header)

    def decrypt(self, blob: bytes) -> bytes:
        header, key_id = self._header(blob)
        body = blob[len(header) :]
        if len(body) < NONCE_LENGTH + TAG_LENGTH:
            raise CryptoError("encrypted value is truncated")
        cipher = self._keys.get(key_id)
        if cipher is None:
            raise CryptoError(f"no key '{key_id}' is configured")
        try:
            return cipher.decrypt(body[:NONCE_LENGTH], body[NONCE_LENGTH:], header)
        except InvalidTag as error:
            raise CryptoError("encrypted value failed authentication") from error

    def key_id_of(self, blob: bytes) -> str:
        return self._header(blob)[1]

    @staticmethod
    def _header(blob: bytes) -> tuple[bytes, str]:
        if len(blob) < 3 or blob[:2] != VERSION:
            raise CryptoError("not an encrypted value of format v1")
        length = blob[2]
        if length == 0 or len(blob) < 3 + length:
            raise CryptoError("encrypted value is truncated")
        key_id = blob[3 : 3 + length].decode("ascii", errors="replace")
        if not _KEY_ID.match(key_id):
            raise CryptoError("encrypted value has an invalid key id")
        return blob[: 3 + length], key_id

    def write_file(self, path: Path, plaintext: bytes) -> None:
        """Encrypt and write atomically (temp file in the same folder, fsync, rename), mode 0640."""
        path.parent.mkdir(parents=True, exist_ok=True)
        blob = self.encrypt(plaintext)
        handle, temp = tempfile.mkstemp(dir=path.parent, prefix=".", suffix=".part")
        try:
            with os.fdopen(handle, "wb") as out:
                out.write(blob)
                out.flush()
                os.fsync(out.fileno())
            os.chmod(temp, FILE_MODE)
            os.replace(temp, path)
        except BaseException:
            Path(temp).unlink(missing_ok=True)
            raise
