from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from el_agent import remote_tasks


def _credentials():
    return {
        "hub_url": "https://hub.example",
        "device_id": "00000000-0000-0000-0000-000000000001",
        "access_token": "a" * 48,
        "refresh_token": "r" * 48,
        "access_expires_at": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
        "refresh_expires_at": (datetime.now(timezone.utc) + timedelta(days=5)).isoformat(),
        "scopes": ["device.heartbeat", "app.launch"],
    }


def test_active_credentials_refreshes_before_task_lease_window(monkeypatch):
    credentials = _credentials()
    credentials["access_expires_at"] = (
        datetime.now(timezone.utc) + timedelta(seconds=30)
    ).isoformat()
    refreshed = {**credentials, "access_token": "b" * 48}
    calls = []
    monkeypatch.setattr(remote_tasks, "load_credentials", lambda: credentials)
    monkeypatch.setattr(
        remote_tasks,
        "_refresh_credentials",
        lambda value: calls.append(value) or refreshed,
    )

    result = remote_tasks._active_credentials()

    assert result["access_token"] == "b" * 48
    assert calls == [credentials]


def test_active_credentials_does_not_refresh_when_token_is_fresh(monkeypatch):
    credentials = _credentials()
    monkeypatch.setattr(remote_tasks, "load_credentials", lambda: credentials)
    monkeypatch.setattr(
        remote_tasks,
        "_refresh_credentials",
        lambda _value: pytest.fail("fresh token must not be refreshed"),
    )

    assert remote_tasks._active_credentials() == credentials
