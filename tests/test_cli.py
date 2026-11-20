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


def test_cli_pair_prompts_for_code_without_command_line_secret(monkeypatch, capsys):
    calls = []
    monkeypatch.setattr(cli.getpass, "getpass", lambda _prompt: "p" * 48)
    monkeypatch.setattr(cli, "pair_device", lambda url, code: calls.append((url, code)) or {"paired": True, "scopes": ["device.heartbeat"]})
    monkeypatch.setattr(sys, "argv", ["el-agent", "pair", "--hub-url", "https://hub.example.test"])
    cli.main()
    assert calls == [("https://hub.example.test", "p" * 48)]
    output = capsys.readouterr().out
    assert "paired" in output
    assert "pppp" not in output


def test_cli_unpair_warns_that_hub_revocation_is_separate(monkeypatch, capsys):
    monkeypatch.setattr(cli, "unpair_local", lambda: {
        "paired": False, "device_id": "device-id", "remote_revocation_required": True
    })
    monkeypatch.setattr(sys, "argv", ["el-agent", "unpair"])
    cli.main()
    captured = capsys.readouterr()
    assert "Revoke this device in the Hub" in captured.err
