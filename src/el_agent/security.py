"""Local token management and constant-time authentication."""
from __future__ import annotations
import hmac
import os
import secrets
from pathlib import Path

def default_data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    return Path(base) / "MR_SIAVASH_EL_AGENT" if base else Path.home() / ".mr_siavash_el_agent"

def token_path() -> Path:
    override = os.environ.get("EL_AGENT_TOKEN_FILE")
    return Path(override).expanduser() if override else default_data_dir() / "api.token"

def create_token(path: Path | None = None) -> str:
    destination = path or token_path()
    destination.parent.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(32)
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(token)
    return token

def load_token(path: Path | None = None) -> str:
    try:
        value = (path or token_path()).read_text(encoding="utf-8").strip()
    except FileNotFoundError as exc:
        raise RuntimeError("API token missing. Run el-agent setup first.") from exc
    if len(value) < 40:
        raise RuntimeError("API token file is invalid.")
    return value

def token_matches(candidate: str | None, expected: str) -> bool:
    return bool(candidate) and hmac.compare_digest(candidate, expected)
