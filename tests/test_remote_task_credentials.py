

def test_active_credentials_refreshes_before_task_lease_window(monkeypatch):
    credentials = _claimed()["credentials"]
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
    credentials = _claimed()["credentials"]
    calls = []
    monkeypatch.setattr(remote_tasks, "load_credentials", lambda: credentials)
    monkeypatch.setattr(
        remote_tasks,
        "_refresh_credentials",
        lambda _value: pytest.fail("fresh token must not be refreshed"),
    )

    assert remote_tasks._active_credentials() == credentials
