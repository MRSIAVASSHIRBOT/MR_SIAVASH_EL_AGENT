"""OS-backed storage for Hub device credentials.

Windows production installs use the Windows Credential Manager backend via
Python keyring. Other platforms retain the private local JSON store for
development and CI; Windows fails closed if a secure backend is unavailable.
"""
from __future__ import annotations

import json
import platform
from typing import Any

SERVICE_NAME = "MR_SIAVASH_EL_AGENT"
CREDENTIAL_USERNAME = "hub-device-credentials"


class CredentialStoreError(RuntimeError):
    """Raised when credentials cannot be stored in the OS credential vault."""


def _windows_keyring():
    try:
        import keyring
    except ImportError as exc:  # pragma: no cover - Windows packaging guard
        raise CredentialStoreError(
            "Windows Credential Manager support is unavailable; install the packaged keyring dependency."
        ) from exc

    backend = keyring.get_keyring()
    backend_name = type(backend).__name__
    if "WinVault" not in backend_name and "Windows" not in backend_name:
        raise CredentialStoreError(
            "No trusted Windows Credential Manager backend is active; credentials were not written."
        )
    return keyring


def save_credentials(credentials: dict[str, Any]) -> None:
    if platform.system() == "Windows":
        keyring = _windows_keyring()
        serialized = json.dumps(credentials, ensure_ascii=False, separators=(",", ":"))
        try:
            keyring.set_password(SERVICE_NAME, CREDENTIAL_USERNAME, serialized)
        except Exception as exc:
            raise CredentialStoreError("Could not save Hub credentials to Windows Credential Manager.") from exc
        return
    raise CredentialStoreError("File-backed credential storage must be handled by the non-Windows development adapter.")


def load_credentials() -> dict[str, Any] | None:
    if platform.system() == "Windows":
        keyring = _windows_keyring()
        try:
            serialized = keyring.get_password(SERVICE_NAME, CREDENTIAL_USERNAME)
        except Exception as exc:
            raise CredentialStoreError("Could not read Hub credentials from Windows Credential Manager.") from exc
        if serialized is None:
            return None
        try:
            value = json.loads(serialized)
        except json.JSONDecodeError as exc:
            raise CredentialStoreError("Windows Credential Manager contains invalid Hub credential data.") from exc
        if not isinstance(value, dict):
            raise CredentialStoreError("Windows Credential Manager contains an invalid credential record.")
        return value
    raise CredentialStoreError("File-backed credential storage must be handled by the non-Windows development adapter.")


def delete_credentials() -> None:
    if platform.system() == "Windows":
        keyring = _windows_keyring()
        try:
            keyring.delete_password(SERVICE_NAME, CREDENTIAL_USERNAME)
        except Exception as exc:
            # keyring's backend raises when the entry does not exist. Unpair is idempotent.
            if "not found" not in str(exc).lower() and "no password" not in str(exc).lower():
                raise CredentialStoreError("Could not delete Hub credentials from Windows Credential Manager.") from exc
        return
    raise CredentialStoreError("File-backed credential storage must be handled by the non-Windows development adapter.")
