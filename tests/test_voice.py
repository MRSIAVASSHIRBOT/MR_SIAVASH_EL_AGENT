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


def test_audio_buffer_overflow_stops_capture_safely(monkeypatch, tmp_path):
    import sys
    from types import SimpleNamespace

    from el_agent.voice import VoiceUnavailable

    model = tmp_path / "model"
    model.mkdir()

    class FakeRecognizer:
        def __init__(self, _model, _sample_rate):
            pass

        def SetWords(self, _enabled):
            pass

        def AcceptWaveform(self, _chunk):
            return False

        def FinalResult(self):
            return '{"text":"ignored"}'

    class FakeInputStream:
        def __init__(self, **kwargs):
            self.callback = kwargs["callback"]

        def __enter__(self):
            # 17 half-second blocks exceed the fixed 16-block (8-second) buffer.
            for _ in range(17):
                self.callback(bytes(16000), 8000, None, None)
            return self

        def __exit__(self, *_args):
            return False

    monkeypatch.setitem(sys.modules, "sounddevice", SimpleNamespace(RawInputStream=FakeInputStream))
    monkeypatch.setitem(
        sys.modules,
        "vosk",
        SimpleNamespace(Model=lambda _path: object(), KaldiRecognizer=FakeRecognizer),
    )

    with pytest.raises(VoiceUnavailable, match="fell behind"):
        listen_once(threading.Event(), max_seconds=15, model_path=model)
