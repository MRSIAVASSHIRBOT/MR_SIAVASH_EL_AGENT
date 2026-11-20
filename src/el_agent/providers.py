"""Provider-neutral text intelligence with local-first, explicit hybrid routing.

Model output is plain text only. This module never executes tools or actions.
"""
from __future__ import annotations

import json
import os
import platform
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


KEYRING_SERVICE = "MR_SIAVASH_EL_AGENT"
KEYRING_USERNAME = "openai-api-key"
OLLAMA_URL = "http://127.0.0.1:11434/api/chat"
OPENAI_URL = "https://api.openai.com/v1/chat/completions"
MAX_PROMPT_CHARS = 8_000
MAX_RESPONSE_BYTES = 1_000_000


class IntelligenceMode(str, Enum):
    LOCAL = "local"
    ONLINE = "online"
    HYBRID = "hybrid"


class ProviderError(RuntimeError):
    """A safe, user-displayable provider failure without secrets or payloads."""


@dataclass(frozen=True)
class ChatResult:
    content: str
    provider: str
    model: str
    mode: str
    latency_ms: int
    fallback_used: bool = False


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _open_json(url: str, payload: dict[str, Any], headers: dict[str, str], timeout: int) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json",
        **headers,
    })
    opener = build_opener(_NoRedirect)
    try:
        with opener.open(request, timeout=timeout) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as exc:
        if exc.code in (301, 302, 303, 307, 308):
            raise ProviderError("Provider redirect refused for credential safety.") from exc
        if exc.code == 401:
            raise ProviderError("Cloud provider rejected the API key.") from exc
        if exc.code == 429:
            raise ProviderError("Cloud provider rate limit reached.") from exc
        raise ProviderError(f"Provider returned HTTP {exc.code}.") from exc
    except (TimeoutError, URLError, OSError) as exc:
        raise ProviderError("Provider is unavailable or the request timed out.") from exc
    if len(raw) > MAX_RESPONSE_BYTES:
        raise ProviderError("Provider response exceeded the size limit.")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProviderError("Provider returned an invalid response.") from exc
    if not isinstance(value, dict):
        raise ProviderError("Provider returned an invalid response shape.")
    return value


def _keyring_module():
    try:
        import keyring
    except ImportError as exc:
        raise ProviderError("Cloud key storage is unavailable. Install the optional cloud dependencies.") from exc
    backend = keyring.get_keyring()
    module = type(backend).__module__
    allowed = {
        "Windows": ("keyring.backends.Windows",),
        "Darwin": ("keyring.backends.macOS",),
        "Linux": ("keyring.backends.SecretService", "keyring.backends.kwallet"),
    }.get(platform.system(), ())
    if getattr(backend, "priority", 0) <= 0 or not any(module.startswith(prefix) for prefix in allowed):
        raise ProviderError("A supported OS credential-store backend is unavailable; cloud credentials were not saved.")
    return keyring


def set_cloud_api_key(value: str) -> None:
    if not isinstance(value, str) or not 20 <= len(value) <= 512 or any(ch.isspace() for ch in value):
        raise ValueError("API key format is invalid.")
    _keyring_module().set_password(KEYRING_SERVICE, KEYRING_USERNAME, value)


def clear_cloud_api_key() -> bool:
    keyring = _keyring_module()
    try:
        keyring.delete_password(KEYRING_SERVICE, KEYRING_USERNAME)
        return True
    except keyring.errors.PasswordDeleteError:
        return False


def cloud_key_configured() -> bool:
    try:
        keyring = _keyring_module()
        return bool(keyring.get_password(KEYRING_SERVICE, KEYRING_USERNAME))
    except ProviderError:
        return False


class OllamaChatProvider:
    """Local Ollama provider restricted to the loopback interface."""

    def __init__(self, model: str | None = None, timeout: int = 45):
        self.model = model or os.environ.get("EL_AGENT_OLLAMA_MODEL", "llama3.1:8b")
        self.timeout = timeout

    def chat(self, prompt: str) -> ChatResult:
        started = time.monotonic()
        data = _open_json(OLLAMA_URL, {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "options": {"num_predict": 512},
        }, {}, self.timeout)
        message = data.get("message")
        content = message.get("content") if isinstance(message, dict) else None
        if not isinstance(content, str) or not content.strip():
            raise ProviderError("Local model returned no text.")
        return ChatResult(content=content.strip(), provider="ollama-local", model=self.model,
                          mode="local", latency_ms=int((time.monotonic() - started) * 1000))


class OpenAIChatProvider:
    """Official OpenAI API adapter; credentials are read only from the OS keyring."""

    def __init__(self, model: str | None = None, timeout: int = 45):
        self.model = model or os.environ.get("EL_AGENT_CLOUD_MODEL", "")
        self.timeout = timeout

    def chat(self, prompt: str) -> ChatResult:
        if not self.model:
            raise ProviderError("Set EL_AGENT_CLOUD_MODEL before using the online provider.")
        keyring = _keyring_module()
        api_key = keyring.get_password(KEYRING_SERVICE, KEYRING_USERNAME)
        if not api_key:
            raise ProviderError("No cloud API key is configured. Use 'el-agent provider-key set'.")
        started = time.monotonic()
        data = _open_json(OPENAI_URL, {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 512,
        }, {"Authorization": f"Bearer {api_key}"}, self.timeout)
        choices = data.get("choices")
        message = choices[0].get("message") if isinstance(choices, list) and choices and isinstance(choices[0], dict) else None
        content = message.get("content") if isinstance(message, dict) else None
        if not isinstance(content, str) or not content.strip():
            raise ProviderError("Cloud model returned no text.")
        return ChatResult(content=content.strip(), provider="openai-api", model=self.model,
                          mode="online", latency_ms=int((time.monotonic() - started) * 1000))


class ProviderRouter:
    """Deterministic provider selection; no model-generated tool execution."""

    def __init__(self, local=None, online=None):
        self.local = local or OllamaChatProvider()
        self.online = online or OpenAIChatProvider()

    def chat(self, prompt: str, mode: IntelligenceMode | str = IntelligenceMode.LOCAL) -> ChatResult:
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_PROMPT_CHARS:
            raise ValueError(f"Prompt must contain 1-{MAX_PROMPT_CHARS} characters.")
        try:
            selected = IntelligenceMode(mode)
        except ValueError as exc:
            raise ValueError("Mode must be local, online, or hybrid.") from exc
        prompt = prompt.strip()
        if selected is IntelligenceMode.LOCAL:
            return self.local.chat(prompt)
        if selected is IntelligenceMode.ONLINE:
            return self.online.chat(prompt)
        try:
            result = self.online.chat(prompt)
            return ChatResult(**{**result.__dict__, "mode": "hybrid"})
        except ProviderError as online_error:
            try:
                result = self.local.chat(prompt)
            except ProviderError:
                raise ProviderError("Online provider failed and the local model is unavailable.") from online_error
            return ChatResult(**{**result.__dict__, "mode": "hybrid", "fallback_used": True})
