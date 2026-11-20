import json
from datetime import datetime, timedelta, timezone

import pytest

from el_agent import hub


class FakeResponse:
    def __init__(self, payload):
        self.payload = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.payload


def _token(char):
    return char * 48


def _token_response(access="a", refresh="r"):
    return {
        "device_id": hub.get_device_id(),
        "access_token": _token(access),
        "refresh_token": _token(refresh),
        "expires_in": 900,
        "refresh_expires_in": 30 * 24 * 60 * 60,
        "scopes": ["device.heartbeat"],
    }


def _use_tmp_data_dir(monkeypatch, tmp_path):
    monkeypatch.setattr(hub, "data_dir", lambda: tmp_path)
    # Keep ordinary unit tests isolated from a developer/runner Windows vault.
    monkeypatch.setattr(hub.platform, "system", lambda: "Linux")


def test_device_id_is_stable_and_pairing_request_is_least_privilege(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    first = hub.get_device_id()
    assert hub.get_device_id() == first
    payload = hub.pairing_challenge_payload()
    assert payload["device_id"] == first
    assert payload["platform"] == "windows"
    assert payload["scopes"] == ["device.heartbeat"]
    assert set(payload["advertised_capabilities"]) == {
        "device.heartbeat", "screen.capture", "app.launch"
    }


def test_hub_url_requires_https_except_loopback():
    assert hub.validate_hub_url("https://hub.example.test/") == "https://hub.example.test"
    assert hub.validate_hub_url("http://127.0.0.1:8000") == "http://127.0.0.1:8000"
    for value in (
        "http://hub.example.test",
        "https://user:password@hub.example.test",
        "https://hub.example.test/api",
        "https://hub.example.test/?token=secret",
        "https://hub.example.test:bad",
    ):
        with pytest.raises(ValueError):
            hub.validate_hub_url(value)


def test_pair_device_saves_tokens_and_returns_only_safe_metadata(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    response = _token_response()
    calls = []

    def fake_urlopen(request, timeout):
        calls.append((request, timeout, json.loads(request.data.decode("utf-8"))))
        return FakeResponse(response)

    monkeypatch.setattr(hub, "urlopen", fake_urlopen)
    result = hub.pair_device("https://hub.example.test", _token("p"))

    assert result["paired"] is True
    assert result["device_id"] == hub.get_device_id()
    assert result["scopes"] == ["device.heartbeat"]
    assert "access_token" not in result
    assert calls[0][0].full_url == "https://hub.example.test/devices/pair"
    assert calls[0][2]["device_id"] == hub.get_device_id()
    saved = json.loads(hub.credentials_path().read_text(encoding="utf-8"))
    assert saved["access_token"] == _token("a")
    assert saved["refresh_token"] == _token("r")
    assert hub.hub_status()["paired"] is True


def test_pair_rejects_invalid_hub_response_without_saving_credentials(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    monkeypatch.setattr(hub, "urlopen", lambda *_args, **_kwargs: FakeResponse({"device_id": hub.get_device_id()}))
    with pytest.raises(hub.HubClientError):
        hub.pair_device("https://hub.example.test", _token("p"))
    assert not hub.credentials_path().exists()


def test_heartbeat_sends_only_minimal_device_health_metadata(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    now = datetime.now(timezone.utc)
    hub._save_credentials({
        "hub_url": "https://hub.example.test",
        "device_id": hub.get_device_id(),
        "access_token": _token("a"),
        "refresh_token": _token("r"),
        "access_expires_at": (now + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    })
    calls = []

    def fake_urlopen(request, timeout):
        calls.append((request, json.loads(request.data.decode("utf-8"))))
        return FakeResponse({"status": "ok", "runtime_state": "ready"})

    monkeypatch.setattr(hub, "urlopen", fake_urlopen)
    result = hub.heartbeat_once()

    assert result["status"] == "ok"
    request, payload = calls[0]
    assert request.full_url.endswith("/devices/heartbeat")
    assert request.get_header("Authorization") == "Bearer " + _token("a")
    assert request.get_header("X-device-id") == hub.get_device_id()
    assert payload["runtime_state"] == "ready"
    assert isinstance(payload["app_uptime_seconds"], int)
    assert set(payload) == {"agent_version", "runtime_state", "app_uptime_seconds"}


def test_expiring_access_token_is_rotated_before_heartbeat(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    now = datetime.now(timezone.utc)
    hub._save_credentials({
        "hub_url": "https://hub.example.test",
        "device_id": hub.get_device_id(),
        "access_token": _token("a"),
        "refresh_token": _token("r"),
        "access_expires_at": (now + timedelta(seconds=20)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    })
    calls = []

    def fake_urlopen(request, timeout):
        body = json.loads(request.data.decode("utf-8"))
        calls.append((request.full_url, request, body))
        if request.full_url.endswith("/devices/refresh"):
            return FakeResponse(_token_response(access="b", refresh="s"))
        return FakeResponse({"status": "ok"})

    monkeypatch.setattr(hub, "urlopen", fake_urlopen)
    result = hub.heartbeat_once()

    assert result["status"] == "ok"
    assert [call[0].rsplit("/", 1)[-1] for call in calls] == ["refresh", "heartbeat"]
    assert calls[0][2]["refresh_token"] == _token("r")
    assert calls[1][1].get_header("Authorization") == "Bearer " + _token("b")
    saved = json.loads(hub.credentials_path().read_text(encoding="utf-8"))
    assert saved["refresh_token"] == _token("s")


def test_unpair_removes_local_credentials_and_flags_remote_revocation(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    now = datetime.now(timezone.utc)
    hub._save_credentials({
        "hub_url": "https://hub.example.test",
        "device_id": hub.get_device_id(),
        "access_token": _token("a"),
        "refresh_token": _token("r"),
        "access_expires_at": (now + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    })
    result = hub.unpair_local()
    assert result["paired"] is False
    assert result["remote_revocation_required"] is True
    assert not hub.credentials_path().exists()


def test_heartbeat_without_pairing_never_contacts_network(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    monkeypatch.setattr(hub, "urlopen", lambda *_args, **_kwargs: pytest.fail("unexpected network request"))
    assert hub.heartbeat_once() == {"paired": False, "status": "not_paired"}


def test_corrupt_stored_hub_url_fails_before_any_network_request(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    now = datetime.now(timezone.utc)
    hub.device_id_path().parent.mkdir(parents=True, exist_ok=True)
    device_id = hub.get_device_id()
    hub._save_credentials({
        "hub_url": "http://attacker.example.test",
        "device_id": device_id,
        "access_token": _token("a"),
        "refresh_token": _token("r"),
        "access_expires_at": (now + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    })
    monkeypatch.setattr(hub, "urlopen", lambda *_args, **_kwargs: pytest.fail("credentials must be rejected before network"))
    with pytest.raises(hub.HubClientError, match="failed validation"):
        hub.heartbeat_once()


def test_device_id_reset_requires_local_unpair_and_creates_new_identity(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    old_id = hub.get_device_id()
    now = datetime.now(timezone.utc)
    hub._save_credentials({
        "hub_url": "https://hub.example.test",
        "device_id": old_id,
        "access_token": _token("a"),
        "refresh_token": _token("r"),
        "access_expires_at": (now + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    })
    with pytest.raises(hub.HubClientError, match="Run unpair first"):
        hub.reset_device_id()
    hub.unpair_local()
    result = hub.reset_device_id()
    assert result["previous_device_id"] == old_id
    assert result["device_id"] != old_id
    assert hub.get_device_id() == result["device_id"]


def test_windows_legacy_credentials_migrate_to_vault_before_plaintext_delete(tmp_path, monkeypatch):
    from el_agent import credential_store

    _use_tmp_data_dir(monkeypatch, tmp_path)
    fake_vault = {}

    class FakeKeyring:
        def get_password(self, service, username):
            return fake_vault.get((service, username))

        def set_password(self, service, username, password):
            fake_vault[(service, username)] = password

        def delete_password(self, service, username):
            fake_vault.pop((service, username), None)

    monkeypatch.setattr(hub.platform, "system", lambda: "Windows")
    monkeypatch.setattr(credential_store, "_windows_keyring", lambda: FakeKeyring())
    now = datetime.now(timezone.utc)
    credentials = {
        "hub_url": "https://hub.example.test",
        "device_id": hub.get_device_id(),
        "access_token": _token("a"),
        "refresh_token": _token("r"),
        "access_expires_at": (now + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    }
    legacy = hub.credentials_path()
    legacy.parent.mkdir(parents=True, exist_ok=True)
    legacy.write_text(json.dumps(credentials), encoding="utf-8")

    assert hub.load_credentials() == credentials
    assert not legacy.exists()
    assert len(fake_vault) == 1



def test_invalid_legacy_credentials_are_not_migrated_or_deleted(tmp_path, monkeypatch):
    from el_agent import credential_store

    _use_tmp_data_dir(monkeypatch, tmp_path)
    fake_vault = {}

    class FakeKeyring:
        def get_password(self, service, username):
            return fake_vault.get((service, username))

        def set_password(self, service, username, password):
            fake_vault[(service, username)] = password

        def delete_password(self, service, username):
            fake_vault.pop((service, username), None)

    monkeypatch.setattr(hub.platform, "system", lambda: "Windows")
    monkeypatch.setattr(credential_store, "_windows_keyring", lambda: FakeKeyring())
    legacy = hub.credentials_path()
    legacy.parent.mkdir(parents=True, exist_ok=True)
    legacy_record = {
        "hub_url": "https://hub.example.test",
        "device_id": hub.get_device_id(),
        "access_token": "short",
        "refresh_token": _token("r"),
        "access_expires_at": (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (datetime.now(timezone.utc) + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    }
    legacy.write_text(json.dumps(legacy_record), encoding="utf-8")

    with pytest.raises(hub.HubClientError, match="failed validation"):
        hub.load_credentials()

    assert legacy.exists(), "invalid plaintext data must be preserved for manual recovery"
    assert fake_vault == {}, "invalid credentials must never be written to the OS vault"


def test_invalid_vault_record_does_not_delete_matching_legacy_copy(tmp_path, monkeypatch):
    from el_agent import credential_store

    _use_tmp_data_dir(monkeypatch, tmp_path)
    fake_vault = {}

    class FakeKeyring:
        def get_password(self, service, username):
            return fake_vault.get((service, username))

        def set_password(self, service, username, password):
            fake_vault[(service, username)] = password

        def delete_password(self, service, username):
            fake_vault.pop((service, username), None)

    monkeypatch.setattr(hub.platform, "system", lambda: "Windows")
    monkeypatch.setattr(credential_store, "_windows_keyring", lambda: FakeKeyring())
    legacy = hub.credentials_path()
    legacy.parent.mkdir(parents=True, exist_ok=True)
    invalid = {
        "hub_url": "https://hub.example.test",
        "device_id": hub.get_device_id(),
        "access_token": "short",
        "refresh_token": _token("r"),
        "access_expires_at": (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (datetime.now(timezone.utc) + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat"],
    }
    legacy.write_text(json.dumps(invalid), encoding="utf-8")
    fake_vault[(credential_store.SERVICE_NAME, credential_store.CREDENTIAL_USERNAME)] = json.dumps(invalid)

    with pytest.raises(hub.HubClientError, match="failed validation"):
        hub.load_credentials()

    assert legacy.exists(), "the legacy copy must remain until the vault record validates"

def test_heartbeat_accepts_additional_explicitly_granted_device_scopes(tmp_path, monkeypatch):
    _use_tmp_data_dir(monkeypatch, tmp_path)
    now = datetime.now(timezone.utc)
    hub._save_credentials({
        "hub_url": "https://hub.example.test",
        "device_id": hub.get_device_id(),
        "access_token": _token("a"),
        "refresh_token": _token("r"),
        "access_expires_at": (now + timedelta(minutes=10)).isoformat(),
        "refresh_expires_at": (now + timedelta(days=10)).isoformat(),
        "scopes": ["device.heartbeat", "app.launch"],
    })
    monkeypatch.setattr(hub, "urlopen", lambda *_args, **_kwargs: FakeResponse({"status": "ok"}))
    assert hub.heartbeat_once()["status"] == "ok"
