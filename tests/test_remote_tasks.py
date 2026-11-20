from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from el_agent import remote_tasks


def _claimed(action="app.launch", app="notepad", *, owner_confirmed=False, scope="app.launch"):
    return {
        "credentials": {
            "hub_url": "https://hub.example",
            "device_id": "00000000-0000-0000-0000-000000000001",
            "access_token": "a" * 48,
            "refresh_token": "r" * 48,
            "access_expires_at": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat(),
            "refresh_expires_at": (datetime.now(timezone.utc) + timedelta(days=5)).isoformat(),
            "scopes": ["device.heartbeat", scope],
        },
        "task": {
            "task_id": "00000000-0000-0000-0000-000000000002",
            "action": action,
            "app": app,
            "required_scope": scope,
            "owner_confirmed": owner_confirmed,
            "timeout_seconds": 15,
        },
        "lease_token": "l" * 48,
        "lease_expires_at": (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat(),
    }


def test_claim_one_returns_none_when_not_paired(monkeypatch):
    monkeypatch.setattr(remote_tasks, "load_credentials", lambda: None)
    assert remote_tasks.claim_one() is None


def test_claim_one_uses_device_auth_and_returns_claim(monkeypatch):
    credentials = _claimed()["credentials"]
    seen = {}

    monkeypatch.setattr(remote_tasks, "_active_credentials", lambda: credentials)

    def fake_post(creds, path, payload):
        seen.update({"path": path, "payload": payload})
        return {
            "task": {"task_id": "00000000-0000-0000-0000-000000000002"},
            "lease_token": "x" * 48,
            "lease_expires_at": (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat(),
        }

    monkeypatch.setattr(remote_tasks, "_post", fake_post)
    result = remote_tasks.claim_one()

    assert result["task"]["task_id"] == "00000000-0000-0000-0000-000000000002"
    assert result["lease_token"] == "x" * 48
    assert datetime.fromisoformat(result["lease_expires_at"]) > datetime.now(timezone.utc)
    assert seen["path"] == "/devices/tasks/claim"


def test_execute_claimed_reports_allowlisted_launch(monkeypatch):
    claimed = _claimed()
    launched = []
    reports = []
    monkeypatch.setattr(remote_tasks, "launch_app", lambda alias: launched.append(alias))
    monkeypatch.setattr(remote_tasks, "_post", lambda creds, path, payload: reports.append((path, payload)) or {
        "task": {"status": "reported_succeeded", "outcome_verified": False}
    })

    result = remote_tasks.execute_claimed(claimed)

    assert launched == ["notepad"]
    assert result["reported_status"] == "reported_succeeded"
    assert result["outcome_verified"] is False
    assert reports[0][0].endswith("/complete")
    assert reports[0][1]["outcome"] == "succeeded"


def test_screenshot_requires_owner_confirmation(monkeypatch):
    claimed = _claimed("screen.capture", None, owner_confirmed=False, scope="screen.capture")
    captured = []
    reports = []
    monkeypatch.setattr(remote_tasks, "capture_screenshot", lambda path: captured.append(path))
    monkeypatch.setattr(remote_tasks, "_post", lambda creds, path, payload: reports.append(payload) or {
        "task": {"status": "reported_failed", "outcome_verified": False}
    })

    result = remote_tasks.execute_claimed(claimed)

    assert captured == []
    assert result["reported_status"] == "reported_failed"
    assert reports[0]["error_code"] == "permission_denied"


def test_screenshot_executes_only_with_owner_confirmation(monkeypatch):
    claimed = _claimed("screen.capture", None, owner_confirmed=True, scope="screen.capture")
    captured = []
    monkeypatch.setattr(remote_tasks, "capture_screenshot", lambda path: captured.append(path))
    monkeypatch.setattr(remote_tasks, "_post", lambda creds, path, payload: {
        "task": {"status": "reported_succeeded", "outcome_verified": False}
    })

    result = remote_tasks.execute_claimed(claimed)

    assert len(captured) == 1
    assert result["reported_status"] == "reported_succeeded"


def test_ungranted_scope_is_rejected_before_action(monkeypatch):
    claimed = _claimed(scope="device.heartbeat")
    launched = []
    monkeypatch.setattr(remote_tasks, "launch_app", lambda alias: launched.append(alias))
    monkeypatch.setattr(remote_tasks, "_post", lambda creds, path, payload: {
        "task": {"status": "reported_failed", "outcome_verified": False}
    })

    result = remote_tasks.execute_claimed(claimed)

    assert launched == []
    assert result["reported_status"] == "reported_failed"


def test_unknown_action_is_never_executed(monkeypatch):
    claimed = _claimed(action="shell.exec", app="cmd", scope="app.launch")
    launched = []
    monkeypatch.setattr(remote_tasks, "launch_app", lambda alias: launched.append(alias))
    monkeypatch.setattr(remote_tasks, "_post", lambda creds, path, payload: {
        "task": {"status": "reported_failed", "outcome_verified": False}
    })

    result = remote_tasks.execute_claimed(claimed)

    assert launched == []
    assert result["reported_status"] == "reported_failed"


def test_claim_one_rejects_expired_lease_before_action(monkeypatch):
    credentials = _claimed()["credentials"]
    monkeypatch.setattr(remote_tasks, "_active_credentials", lambda: credentials)
    monkeypatch.setattr(
        remote_tasks,
        "_post",
        lambda *_args, **_kwargs: {
            "task": {"task_id": "00000000-0000-0000-0000-000000000002"},
            "lease_token": "x" * 48,
            "lease_expires_at": (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat(),
        },
    )

    with pytest.raises(remote_tasks.HubClientError, match="expired"):
        remote_tasks.claim_one()


def test_expired_lease_prevents_action_and_completion_post(monkeypatch):
    claimed = _claimed()
    claimed["lease_expires_at"] = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    launched = []
    monkeypatch.setattr(remote_tasks, "launch_app", lambda alias: launched.append(alias))
    monkeypatch.setattr(
        remote_tasks, "_post",
        lambda *_args, **_kwargs: pytest.fail("expired lease must not be reported"),
    )

    result = remote_tasks.execute_claimed(claimed)

    assert launched == []
    assert result["reported_status"] == "not_reported_lease_expired"
    assert result["action_attempted"] is False


def test_action_finishing_after_lease_expiry_is_not_reported_or_replayed(monkeypatch):
    now = datetime.now(timezone.utc)
    lease_expiry = now + timedelta(seconds=30)
    clock = [now]

    claimed = _claimed()
    claimed["lease_expires_at"] = lease_expiry.isoformat()
    launched = []

    def launch_and_advance_clock(alias):
        launched.append(alias)
        clock[0] = lease_expiry + timedelta(milliseconds=1)

    monkeypatch.setattr(remote_tasks, "_utc_now", lambda: clock[0])
    monkeypatch.setattr(remote_tasks, "launch_app", launch_and_advance_clock)
    monkeypatch.setattr(
        remote_tasks, "_post",
        lambda *_args, **_kwargs: pytest.fail("late task outcome must not be posted"),
    )

    result = remote_tasks.execute_claimed(claimed)

    assert launched == ["notepad"]
    assert result["reported_status"] == "not_reported_lease_expired"
    assert result["action_attempted"] is True


    class ControlledDateTime(real_datetime):
        current = started_at

        @classmethod
        def now(cls, tz=None):
            return cls.current if tz is not None else cls.current.replace(tzinfo=None)

    claimed = _claimed()
    claimed["lease_expires_at"] = lease_expiry.isoformat()
    launched = []

    def launch_and_advance_clock(alias):
        launched.append(alias)
        ControlledDateTime.current = lease_expiry + timedelta(milliseconds=1)

    monkeypatch.setattr(remote_tasks, "datetime", ControlledDateTime)
    monkeypatch.setattr(remote_tasks, "launch_app", launch_and_advance_clock)
    monkeypatch.setattr(
        remote_tasks, "_post",
        lambda *_args, **_kwargs: pytest.fail("late task outcome must not be posted"),
    )

    result = remote_tasks.execute_claimed(claimed)

    assert launched == ["notepad"]
    assert result["reported_status"] == "not_reported_lease_expired"
    assert result["action_attempted"] is True
