"""Opt-in, local-only push-to-talk speech input and speech output."""
from __future__ import annotations

import importlib.util
import json
import os
import queue
import threading
import time
from pathlib import Path


class VoiceUnavailable(RuntimeError):
    """Raised when optional local voice dependencies or models are unavailable."""


def resolve_model_path(model_path: str | Path | None = None) -> Path:
    raw = model_path if model_path is not None else os.environ.get("EL_AGENT_VOSK_MODEL")
    if not raw:
        raise VoiceUnavailable(
            "Persian speech model is not configured. Set EL_AGENT_VOSK_MODEL to the extracted Vosk model directory."
        )
    path = Path(raw).expanduser()
    if not path.is_dir():
        raise VoiceUnavailable("Vosk model directory does not exist: " + str(path))
    return path.resolve()


def get_voice_setup_status() -> dict[str, object]:
    missing = [
        package for package in ("sounddevice", "vosk", "pyttsx3")
        if importlib.util.find_spec(package) is None
    ]
    model_value = os.environ.get("EL_AGENT_VOSK_MODEL")
    model_path = Path(model_value).expanduser() if model_value else None
    model_ready = bool(model_path and model_path.is_dir())
    return {
        "ready": not missing and model_ready,
        "missing_packages": missing,
        "model_configured": model_ready,
        "model_path": str(model_path.resolve()) if model_ready and model_path else None,
        "mode": "local",
        "always_listening": False,
        "cloud_audio": False,
    }


def listen_once(
    stop_event: threading.Event,
    *,
    max_seconds: int = 15,
    model_path: str | Path | None = None,
) -> str:
    """Capture one bounded utterance after an explicit UI action; never uploads audio."""
    if not 1 <= max_seconds <= 60:
        raise ValueError("max_seconds must be between 1 and 60")

    selected_model = resolve_model_path(model_path)
    try:
        import sounddevice as sd
        from vosk import KaldiRecognizer, Model
    except ImportError as exc:
        raise VoiceUnavailable(
            'Install optional voice dependencies with: python -m pip install -e ".[voice]"'
        ) from exc

    try:
        model = Model(str(selected_model))
        recognizer = KaldiRecognizer(model, 16000)
        recognizer.SetWords(True)
    except Exception as exc:
        raise VoiceUnavailable("Could not load the local Vosk speech model.") from exc

    audio_queue: queue.Queue[bytes] = queue.Queue()
    segments: list[str] = []

    def audio_callback(indata, _frames, _time_info, status):
        if status:
            # Device status is surfaced by sounddevice exceptions where possible;
            # do not log or persist microphone buffers.
            pass
        audio_queue.put(bytes(indata))

    deadline = time.monotonic() + max_seconds
    try:
        with sd.RawInputStream(
            samplerate=16000,
            blocksize=8000,
            dtype="int16",
            channels=1,
            callback=audio_callback,
        ):
            while not stop_event.is_set() and time.monotonic() < deadline:
                try:
                    chunk = audio_queue.get(timeout=0.25)
                except queue.Empty:
                    continue
                if recognizer.AcceptWaveform(chunk):
                    text = json.loads(recognizer.Result()).get("text", "").strip()
                    if text:
                        segments.append(text)
            final_text = json.loads(recognizer.FinalResult()).get("text", "").strip()
            if final_text:
                segments.append(final_text)
    except VoiceUnavailable:
        raise
    except Exception as exc:
        raise VoiceUnavailable(
            "Microphone capture failed. Check Windows microphone privacy settings and the selected input device."
        ) from exc

    transcript = " ".join(segments).strip()
    if not transcript:
        raise VoiceUnavailable("No speech was recognized. Try again and speak clearly.")
    return transcript


def speak_text(text: str) -> None:
    """Speak a short response using the user's installed local system voice."""
    if not text or len(text) > 500:
        raise ValueError("Speech output must contain between 1 and 500 characters.")
    try:
        import pyttsx3
    except ImportError as exc:
        raise VoiceUnavailable(
            'Install optional voice dependencies with: python -m pip install -e ".[voice]"'
        ) from exc
    try:
        engine = pyttsx3.init()
        engine.say(text)
        engine.runAndWait()
        engine.stop()
    except Exception as exc:
        raise VoiceUnavailable(
            "Local text-to-speech failed. Check that a compatible Windows speech voice is installed."
        ) from exc
