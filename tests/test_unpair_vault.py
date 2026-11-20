from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json

import pytest

from el_agent import credential_store, hub


class FakeKeyring:
    def __init__(self, *, fail_delete: bool = False):
        self.values = {}
        self.fail_delete = fail_delete

    def get_password(self, service, username):
        return self.values.get((service, username))

    def set_password(self, service, username, password):
        self.values[(service, username)] = password

    def delete_password(self, service, username):
        if self.fail_delete:
            raise RuntimeError("simulated vault delete failure")
        self.values.pop((service, username), None)


def _credential_record(device_id: str) -> dict[str, object]:
    now = datetime.now(timezone.utc)
    return {
        "hub_url": "https://hub.example.test",
        "device_id": device_id,
        "access_token": "a" * 48,
        "refresh_token": "r" * 48,
        "access_expires_at": (now + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    }


def _windows_vault(monkeypatch, tmp_path, *, fail_delete=False):
    keyring = FakeKeyring(fail_delete=fail_delete)
    monkeypatch.setattr(hub, "data_dir", lambda: tmp_path)
    monkeypatch.setattr(hub.platform, "system", lambda: "Windows")
    monkeypatch.setattr(credential_store.platform, "system", lambda: "Windows")
    monkeypatch.setattr(credential_store, "_windows_keyring", lambda: keyring)
    device_id = hub.get_device_id()
    return keyring, _credential_record(device_id)


def test_unpair_removes_valid_windows_vault_credentials(tmp_path, monkeypatch):
    keyring, credentials = _windows_vault(monkeypatch, tmp_path)
    credential_store.save_credentials(credentials)

    result = hub.unpair_local()

    assert result["paired"] is False
    assert result["device_id"] == credentials["device_id"]
    assert result["remote_revocation_required"] is True
    assert credential_store.has_credentials() is False
    assert credential_store.load_credentials() is None
    assert not hub.credentials_path().exists()


def test_unpair_removes_corrupt_windows_vault_value_without_parsing_it(tmp_path, monkeypatch):
    keyring, credentials = _windows_vault(monkeypatch, tmp_path)
    keyring.values[(credential_store.SERVICE_NAME, credential_store.CREDENTIAL_USERNAME)] = "{invalid-json"

    result = hub.unpair_local()

    assert result["remote_revocation_required"] is True
    assert credential_store.has_credentials() is False
    assert not hub.credentials_path().exists()


def test_unpair_fails_closed_and_preserves_legacy_copy_if_vault_delete_fails(tmp_path, monkeypatch):
    keyring, credentials = _windows_vault(monkeypatch, tmp_path, fail_delete=True)
    credential_store.save_credentials(credentials)
    legacy = hub.credentials_path()
    legacy.write_text(json.dumps(credentials), encoding="utf-8")

    with pytest.raises(hub.HubClientError, match="Credential Manager"):
        hub.unpair_local()

    assert credential_store.has_credentials() is True
    assert legacy.exists(), "legacy recovery copy must remain when vault removal fails"


def test_unpair_is_idempotent_when_vault_and_legacy_store_are_empty(tmp_path, monkeypatch):
    _windows_vault(monkeypatch, tmp_path)

    result = hub.unpair_local()

    assert result["paired"] is False
    assert result["remote_revocation_required"] is False
    assert credential_store.has_credentials() is False
