"""Local token management and constant-time authentication."""
from __future__ import annotations

import hmac
import os
import re
import secrets
import stat
from pathlib import Path

_TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_-]{40,128}\Z")
_MAX_TOKEN_FILE_BYTES = 128


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
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(destination, flags, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii", newline="") as stream:
            descriptor = -1
            stream.write(token)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        # Do not leave a partial credential file behind after an interrupted write.
        try:
            destination.unlink()
        except OSError:
            pass
        raise
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    return token


def load_token(path: Path | None = None) -> str:
    destination = path or token_path()
    # O_NOFOLLOW prevents a substituted symlink from redirecting credential reads
    # on platforms that support it. fstat also rejects directories, pipes and devices.
    if not hasattr(os, "O_NOFOLLOW") and destination.is_symlink():
        raise RuntimeError("API token file could not be safely read.")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = -1
    try:
        descriptor = os.open(destination, flags)
        file_stat = os.fstat(descriptor)
        if not stat.S_ISREG(file_stat.st_mode):
            raise RuntimeError("API token file must be a regular file.")
        if not 40 <= file_stat.st_size <= _MAX_TOKEN_FILE_BYTES:
            raise RuntimeError("API token file is invalid.")
        with os.fdopen(descriptor, "r", encoding="ascii", newline="") as stream:
            descriptor = -1
            value = stream.read(_MAX_TOKEN_FILE_BYTES + 1)
    except FileNotFoundError as exc:
        raise RuntimeError("API token missing. Run el-agent setup first.") from exc
    except (OSError, UnicodeError) as exc:
        raise RuntimeError("API token file could not be safely read.") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)

    if not isinstance(value, str) or _TOKEN_PATTERN.fullmatch(value) is None:
        raise RuntimeError("API token file is invalid.")
    return value


def token_matches(candidate: str | None, expected: str) -> bool:
    return bool(candidate) and hmac.compare_digest(candidate, expected)
