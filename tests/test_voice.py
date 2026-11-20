import threading

import pytest

from el_agent.voice import VoiceUnavailable, listen_once, resolve_model_path


def test_model_path_requires_explicit_configuration(monkeypatch):
    monkeypatch.delenv("EL_AGENT_VOSK_MODEL", raising=False)
    with pytest.raises(VoiceUnavailable, match="not configured"):
        resolve_model_path()


def test_model_path_must_be_an_existing_directory(monkeypatch, tmp_path):
    missing = tmp_path / "missing-model"
    monkeypatch.setenv("EL_AGENT_VOSK_MODEL", str(missing))
    with pytest.raises(VoiceUnavailable, match="does not exist"):
        resolve_model_path()


def test_model_path_resolves_existing_directory(monkeypatch, tmp_path):
    model = tmp_path / "vosk-model-small-fa"
    model.mkdir()
    monkeypatch.setenv("EL_AGENT_VOSK_MODEL", str(model))
    assert resolve_model_path() == model.resolve()


def test_listen_duration_is_bounded_before_loading_dependencies():
    with pytest.raises(ValueError, match="between 1 and 60"):
        listen_once(threading.Event(), max_seconds=0)


def test_listen_duration_rejects_unbounded_capture():
    with pytest.raises(ValueError, match="between 1 and 60"):
        listen_once(threading.Event(), max_seconds=61)
