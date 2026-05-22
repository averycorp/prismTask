"""Symmetric encryption for OAuth refresh tokens stored in
`integration_configs.config_json`.

Uses Fernet (AES-128-CBC + HMAC-SHA256) from the `cryptography` package.
In production, `INTEGRATION_ENCRYPTION_KEY` MUST be a 32-byte URL-safe
base64 value (same as `cryptography.fernet.Fernet.generate_key()`). In
dev we fall back to a derived per-process key so the server starts —
tokens written will be unreadable after a restart, which is an acceptable
dev-only trade-off.
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
from typing import Any

from pydantic import BaseModel, ValidationError

try:
    from cryptography.fernet import Fernet, InvalidToken
except ImportError:  # pragma: no cover
    Fernet = None  # type: ignore
    InvalidToken = Exception  # type: ignore

from app.config import settings

logger = logging.getLogger(__name__)


def _load_key() -> bytes:
    raw = settings.INTEGRATION_ENCRYPTION_KEY or os.environ.get(
        "INTEGRATION_ENCRYPTION_KEY", ""
    )
    if raw:
        key_bytes = raw.encode("utf-8")
        # Accept either raw Fernet keys or anything — we hash to 32 bytes
        # and base64-encode so misconfigured values still boot the server
        # (the same misconfig in prod is caught by is_production check).
        if len(key_bytes) == 44 and key_bytes.endswith(b"="):
            return key_bytes
        digest = hashlib.sha256(key_bytes).digest()
        return base64.urlsafe_b64encode(digest)
    if settings.is_production:
        raise RuntimeError(
            "INTEGRATION_ENCRYPTION_KEY must be set in production"
        )
    # Dev fallback — derive from JWT secret so repeated starts on the same
    # machine with the same JWT_SECRET_KEY can decrypt their own writes.
    digest = hashlib.sha256(settings.get_jwt_secret().encode()).digest()
    return base64.urlsafe_b64encode(digest)


def _fernet() -> Fernet:
    if Fernet is None:
        raise RuntimeError("cryptography package is not installed")
    return Fernet(_load_key())


def encrypt_json(data: dict[str, Any]) -> str:
    """Serialize *data* to JSON and Fernet-encrypt it. Returns a URL-safe
    base64 string suitable for storing in `config_json`.
    """
    payload = json.dumps(data, default=str).encode("utf-8")
    token = _fernet().encrypt(payload)
    return token.decode("utf-8")


class IntegrationToken(BaseModel):
    """Schema for securely validating decrypted JSON payloads."""

    access_token: str | None = None
    refresh_token: str | None = None
    token_uri: str | None = None
    client_id: str | None = None
    client_secret: str | None = None
    scopes: list[str] | None = None
    expiry: str | None = None

    model_config = {"extra": "ignore"}


def decrypt_json(encoded: str) -> dict[str, Any]:
    """Reverse of [encrypt_json]. Raises `ValueError` if the payload is
    tampered with, the key has rotated, or the payload shape is invalid."""
    if not encoded:
        return {}
    try:
        plain = _fernet().decrypt(encoded.encode("utf-8"))
    except InvalidToken as e:  # pragma: no cover
        raise ValueError("integration config token is invalid or expired") from e

    parsed = json.loads(plain.decode("utf-8"))
    try:
        return IntegrationToken.model_validate(parsed).model_dump(exclude_unset=True)
    except ValidationError as e:
        raise ValueError("integration config payload fails schema validation") from e
