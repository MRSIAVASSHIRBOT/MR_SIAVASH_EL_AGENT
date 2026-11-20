import sys

import pytest

from el_agent import cli


def test_cli_ask_runs_allowlisted_persian_app_launch(monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "api_call", lambda method, path, payload=None: calls.append((method, path, payload)) or {"started": True})
    monkeypatch.setattr(sys, "argv", ["el-agent", "ask", "برو", "داخل", "چت", "جی", "پی", "تی"])
    cli.main()
    assert calls == [("POST", "/v1/apps/chatgpt/launch", None)]


def test_cli_ask_runs_explicit_screenshot(monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "api_call", lambda method, path, payload=None: calls.append((method, path, payload)) or {"filename": "screen-test.png"})
    monkeypatch.setattr(sys, "argv", ["el-agent", "ask", "اسکرین", "شات", "بگیر"])
    cli.main()
    assert calls == [("POST", "/v1/screenshot", None)]


def test_cli_ask_rejects_unknown_commands_without_api_call(monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "api_call", lambda *args, **kwargs: calls.append((args, kwargs)))
    monkeypatch.setattr(sys, "argv", ["el-agent", "ask", "run", "powershell"])
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 1
    assert calls == []
