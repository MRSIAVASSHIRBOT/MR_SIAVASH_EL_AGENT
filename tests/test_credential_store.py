import json

import pytest

from el_agent import credential_store


class FakeKeyring:
    def __init__(self):
        self.values = {}

    def set_password(self, service, username, password):
        self.values[(service, username)] = password

    def get_password(self, service, username):
        return self.values.get((service, username))

    def delete_password(self, service, username):
        self.values.pop((service, username), None)


def test_windows_vault_round_trip_and_delete(monkeypatch):
    fake = FakeKeyring()
    monkeypatch.setattr(credential_store.platform, "system", lambda: "Windows")
    monkeypatch.setattr(credential_store, "_windows_keyring", lambda: fake)
    credentials = {"access_token": "a" * 48, "scopes": ["device.heartbeat"]}

    credential_store.save_credentials(credentials)
    assert credential_store.load_credentials() == credentials
    assert json.loads(fake.values[(credential_store.SERVICE_NAME, credential_store.CREDENTIAL_USERNAME)]) == credentials

    credential_store.delete_credentials()
    assert credential_store.load_credentials() is None


def test_windows_vault_missing_backend_fails_closed(monkeypatch):
    monkeypatch.setattr(credential_store.platform, "system", lambda: "Windows")
    monkeypatch.setattr(
        credential_store,
        "_windows_keyring",
        lambda: (_ for _ in ()).throw(credential_store.CredentialStoreError("no trusted backend")),
    )
    with pytest.raises(credential_store.CredentialStoreError):
        credential_store.save_credentials({"access_token": "secret"})


def test_non_windows_backend_is_not_silently_used(monkeypatch):
    monkeypatch.setattr(credential_store.platform, "system", lambda: "Linux")
    with pytest.raises(credential_store.CredentialStoreError):
        credential_store.save_credentials({"access_token": "secret"})
