"""Least-privilege client for owner-initiated MR_SIAVASH_HUB_AI device pairing."""
from __future__ import annotations

import json
import os
import platform
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from uuid import UUID, uuid4

from el_agent.service import data_dir

AGENT_VERSION = "0.2.0"
HEARTBEAT_INTERVAL_SECONDS = 60
HEARTBEAT_REFRESH_WINDOW_SECONDS = 120
AGENT_STARTED_AT = time.monotonic()


class HubClientError(RuntimeError):
    """Raised when Hub pairing or device synchronization cannot complete."""


def device_id_path() -> Path:
    return data_dir() / "device-id.txt"


def credentials_path() -> Path:
    return data_dir() / "hub-credentials.json"


def get_device_id() -> str:
    path = device_id_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        try:
            return str(UUID(path.read_text(encoding="utf-8").strip()))
        except (ValueError, OSError) as exc:
            raise HubClientError("The local device identifier is invalid; do not overwrite it automatically.") from exc
    value = str(uuid4())
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        return get_device_id()
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(value)
    return value


def pairing_challenge_payload() -> dict[str, object]:
    return {
        "device_id": get_device_id(),
        "platform": "windows",
        "label": "Windows personal assistant",
        "advertised_capabilities": ["device.heartbeat", "screen.capture", "app.launch"],
        "scopes": ["device.heartbeat"],
    }


def validate_hub_url(value: str) -> str:
    parsed = urlsplit(value.strip())
    try:
        parsed.port
    except ValueError as exc:
        raise ValueError("Hub URL contains an invalid port.") from exc
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Hub URL must contain a hostname and no credentials, query, or fragment.")
    local_hosts = {"localhost", "127.0.0.1", "::1"}
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in local_hosts):
        raise ValueError("Hub URL must use HTTPS; HTTP is allowed only for localhost.")
    if parsed.path not in ("", "/"):
        raise ValueError("Hub URL must be the Hub origin without an API path.")
    return f"{parsed.scheme}://{parsed.netloc}"


def _request_json(
    base_url: str,
    path: str,
    *,
    method: str,
    payload: dict[str, object],
    headers: dict[str, str] | None = None,
) -> dict[str, object]:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    request = Request(base_url + path, data=body, method=method, headers=request_headers)
    try:
        with urlopen(request, timeout=12) as response:
            raw = response.read()
    except HTTPError as exc:
        raise HubClientError(f"Hub rejected the device request (HTTP {exc.code}).") from exc
    except (URLError, TimeoutError, OSError) as exc:
        raise HubClientError("Hub is unreachable; check the Hub URL and network connection.") from exc
    try:
        result = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HubClientError("Hub returned an invalid response.") from exc
    if not isinstance(result, dict):
        raise HubClientError("Hub returned an unexpected response shape.")
    return result


def _validated_tokens(result: dict[str, object]) -> dict[str, object]:
    access = result.get("access_token")
    refresh = result.get("refresh_token")
    access_ttl = result.get("expires_in")
    refresh_ttl = result.get("refresh_expires_in")
    scopes = result.get("scopes")
    if (
        not isinstance(access, str) or len(access) < 40
        or not isinstance(refresh, str) or len(refresh) < 40
        or not isinstance(access_ttl, int) or not 30 <= access_ttl <= 3600
        or not isinstance(refresh_ttl, int) or not 3600 <= refresh_ttl <= 90 * 24 * 3600
        or not isinstance(scopes, list) or not all(isinstance(scope, str) for scope in scopes)
        or not {"device.heartbeat"}.issubset(set(scopes))
        or not set(scopes).issubset({"device.heartbeat", "app.launch", "screen.capture"})
    ):
        raise HubClientError("Hub returned invalid device credentials.")
    now = datetime.now(timezone.utc)
    return {
        "access_token": access,
        "refresh_token": refresh,
        "access_expires_at": (now + timedelta(seconds=access_ttl)).isoformat(),
        "refresh_expires_at": (now + timedelta(seconds=refresh_ttl)).isoformat(),
        "scopes": sorted(set(scopes)),
    }


def _save_credentials(credentials: dict[str, object]) -> None:
    if platform.system() == "Windows":
        from el_agent.credential_store import load_credentials as load_vault_credentials
        from el_agent.credential_store import save_credentials
        try:
            save_credentials(credentials)
            stored = load_vault_credentials()
            if stored != credentials:
                raise HubClientError("Windows Credential Manager did not preserve the credential record.")
            legacy_path = credentials_path()
            if legacy_path.exists():
                legacy = json.loads(legacy_path.read_text(encoding="utf-8"))
                if not isinstance(legacy, dict):
                    raise HubClientError("Legacy Hub credential file has an invalid shape; it was preserved.")
                _validate_credential_record(legacy)
                if (
                    legacy.get("device_id") != credentials.get("device_id")
                    or legacy.get("hub_url") != credentials.get("hub_url")
                ):
                    raise HubClientError("Legacy credentials belong to a different device or Hub; manual review is required.")
                legacy_path.unlink()
        except HubClientError:
            raise
        except Exception as exc:
            raise HubClientError("Hub credentials could not be verified in Windows Credential Manager or legacy plaintext credentials could not be safely reconciled.") from exc
        return
    path = credentials_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix="hub-credentials-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(credentials, stream, ensure_ascii=False, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.chmod(temporary_name, 0o600)
        except OSError:
            pass
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def _validate_credential_record(result: dict[str, object]) -> dict[str, object]:
    """Validate the complete record before returning or migrating it."""
    required = {
        "hub_url", "device_id", "access_token", "refresh_token",
        "access_expires_at", "refresh_expires_at", "scopes",
    }
    if not required.issubset(result):
        raise HubClientError("Local Hub credentials are incomplete.")
    try:
        hub_url = result["hub_url"]
        if not isinstance(hub_url, str) or validate_hub_url(hub_url) != hub_url:
            raise ValueError("Hub URL is not canonical.")
        device_id = result["device_id"]
        canonical_device_id = str(UUID(str(device_id)))
        if canonical_device_id != device_id:
            raise ValueError("Device identity is not canonical.")
        identity_path = device_id_path()
        if not identity_path.is_file() or identity_path.read_text(encoding="utf-8").strip() != canonical_device_id:
            raise ValueError("Stored device identity does not match this installation.")
        for key in ("access_token", "refresh_token"):
            token = result[key]
            if not isinstance(token, str) or not 40 <= len(token) <= 256:
                raise ValueError(f"{key} is invalid.")
        scopes = result["scopes"]
        allowed_scopes = {"device.heartbeat", "app.launch", "screen.capture"}
        if (
            not isinstance(scopes, list)
            or not all(isinstance(scope, str) for scope in scopes)
            or len(scopes) != len(set(scopes))
            or "device.heartbeat" not in scopes
            or not set(scopes).issubset(allowed_scopes)
        ):
            raise ValueError("Stored device scopes are invalid.")
        for key in ("access_expires_at", "refresh_expires_at"):
            expiry = datetime.fromisoformat(str(result[key]))
            if expiry.tzinfo is None or expiry.utcoffset() is None:
                raise ValueError("Credential expiry timestamps must be timezone-aware.")
    except (ValueError, OSError, TypeError) as exc:
        raise HubClientError("Local Hub credentials failed validation; no network request was sent.") from exc
    return result


def _read_credentials_raw() -> dict[str, object] | None:
    if platform.system() == "Windows":
        from el_agent.credential_store import load_credentials, save_credentials
        try:
            stored = load_credentials()
            legacy_path = credentials_path()
            if stored is not None:
                _validate_credential_record(stored)
                if legacy_path.exists():
                    legacy = json.loads(legacy_path.read_text(encoding="utf-8"))
                    if not isinstance(legacy, dict):
                        raise HubClientError("Legacy Hub credential file has an invalid shape.")
                    _validate_credential_record(legacy)
                    if legacy != stored:
                        raise HubClientError("Windows vault and legacy credential file differ; manual review is required.")
                    # Only remove the plaintext copy after both records pass
                    # the same schema/identity validation and match exactly.
                    legacy_path.unlink()
                return stored

            # One-time migration: validate before writing, read back and
            # compare after the vault write, then remove plaintext.
            if legacy_path.exists():
                legacy = json.loads(legacy_path.read_text(encoding="utf-8"))
                if not isinstance(legacy, dict):
                    raise HubClientError("Legacy Hub credential file has an invalid shape.")
                _validate_credential_record(legacy)
                save_credentials(legacy)
                migrated = load_credentials()
                if migrated != legacy:
                    raise HubClientError("Windows Credential Manager did not preserve the migrated credential record.")
                _validate_credential_record(migrated)
                legacy_path.unlink()
                return migrated
            return None
        except HubClientError:
            raise
        except Exception as exc:
            raise HubClientError("Windows Credential Manager could not safely load or migrate Hub credentials.") from exc

    path = credentials_path()
    if not path.exists():
        return None
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise HubClientError("Local Hub credentials are unreadable; do not delete or overwrite them automatically.") from exc
    if not isinstance(result, dict):
        raise HubClientError("Local Hub credentials have an invalid shape.")
    return result

def load_credentials() -> dict[str, object] | None:
    result = _read_credentials_raw()
    if result is None:
        return None
    if not isinstance(result, dict):
        raise HubClientError("Local Hub credentials have an invalid shape.")
    return _validate_credential_record(result)


def _delete_credentials() -> None:
    if platform.system() == "Windows":
        from el_agent.credential_store import delete_credentials
        try:
            delete_credentials()
        except Exception as exc:
            raise HubClientError("Could not remove Hub credentials from Windows Credential Manager.") from exc
    path = credentials_path()
    if path.exists():
        path.unlink()

def pair_device(hub_url: str, pairing_code: str) -> dict[str, object]:
    base_url = validate_hub_url(hub_url)
    if not isinstance(pairing_code, str) or len(pairing_code) < 40 or len(pairing_code) > 128:
        raise ValueError("Pairing code format is invalid.")
    device_id = get_device_id()
    result = _request_json(
        base_url,
        "/devices/pair",
        method="POST",
        payload={
            "pairing_code": pairing_code,
            "device_id": device_id,
            "agent_version": AGENT_VERSION,
            "os_version": platform.platform()[:64],
        },
    )
    if result.get("device_id") != device_id:
        raise HubClientError("Hub paired a different device identifier; credentials were not saved.")
    credentials = {
        "hub_url": base_url,
        "device_id": device_id,
        **_validated_tokens(result),
    }
    _save_credentials(credentials)
    return {
        "paired": True,
        "device_id": device_id,
        "hub_url": base_url,
        "scopes": credentials["scopes"],
        "access_expires_at": credentials["access_expires_at"],
    }


def _refresh_credentials(credentials: dict[str, object]) -> dict[str, object]:
    result = _request_json(
        str(credentials["hub_url"]),
        "/devices/refresh",
        method="POST",
        payload={
            "refresh_token": str(credentials["refresh_token"]),
            "device_id": str(credentials["device_id"]),
        },
    )
    if result.get("device_id") != credentials["device_id"]:
        raise HubClientError("Hub returned credentials for a different device.")
    updated = {
        "hub_url": credentials["hub_url"],
        "device_id": credentials["device_id"],
        **_validated_tokens(result),
    }
    _save_credentials(updated)
    return updated


def heartbeat_once() -> dict[str, object]:
    credentials = load_credentials()
    if credentials is None:
        return {"paired": False, "status": "not_paired"}

    now = datetime.now(timezone.utc)
    try:
        access_expiry = datetime.fromisoformat(str(credentials["access_expires_at"]))
        refresh_expiry = datetime.fromisoformat(str(credentials["refresh_expires_at"]))
    except ValueError as exc:
        raise HubClientError("Local Hub credential expiry metadata is invalid.") from exc
    if access_expiry.tzinfo is None or refresh_expiry.tzinfo is None:
        raise HubClientError("Local Hub credential expiry metadata must include a timezone.")
    if not isinstance(credentials.get("scopes"), list) or "device.heartbeat" not in credentials["scopes"]:
        raise HubClientError("Local Hub credentials do not include the required heartbeat scope.")
    if refresh_expiry <= now:
        raise HubClientError("Hub refresh credential expired; owner-initiated re-pairing is required.")
    if access_expiry <= now + timedelta(seconds=HEARTBEAT_REFRESH_WINDOW_SECONDS):
        credentials = _refresh_credentials(credentials)

    response = _request_json(
        str(credentials["hub_url"]),
        "/devices/heartbeat",
        method="POST",
        payload={
            "agent_version": AGENT_VERSION,
            "runtime_state": "ready",
            "app_uptime_seconds": max(0, int(time.monotonic() - AGENT_STARTED_AT)),
        },
        headers={
            "Authorization": "Bearer " + str(credentials["access_token"]),
            "X-Device-ID": str(credentials["device_id"]),
        },
    )
    return {"paired": True, "status": response.get("status", "unknown"), "device_id": credentials["device_id"]}


def hub_status() -> dict[str, object]:
    credentials = load_credentials()
    if credentials is None:
        return {"paired": False, "device_id": get_device_id(), "status": "not_paired"}
    return {
        "paired": True,
        "device_id": credentials["device_id"],
        "hub_url": credentials["hub_url"],
        "scopes": credentials["scopes"],
        "access_expires_at": credentials["access_expires_at"],
        "refresh_expires_at": credentials["refresh_expires_at"],
    }


def unpair_local() -> dict[str, object]:
    credentials = load_credentials()
    path = credentials_path()
    if path.exists():
        path.unlink()
    return {
        "paired": False,
        "device_id": credentials["device_id"] if credentials else (device_id_path().read_text(encoding="utf-8").strip() if device_id_path().exists() else None),
        "remote_revocation_required": credentials is not None,
    }


def reset_device_id() -> dict[str, object]:
    # Credentials may live in Windows Credential Manager rather than the
    # legacy JSON path. Validate both storage backends before changing identity.
    if load_credentials() is not None or credentials_path().exists():
        raise HubClientError("Run unpair first; reset is blocked while local Hub credentials remain.")
    path = device_id_path()
    previous = path.read_text(encoding="utf-8").strip() if path.exists() else None
    if path.exists():
        path.unlink()
    return {"previous_device_id": previous, "device_id": get_device_id(), "paired": False}
