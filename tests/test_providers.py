from el_agent.providers import (
    ChatResult, IntelligenceMode, ProviderError, ProviderRouter,
    MAX_PROMPT_CHARS, OllamaChatProvider, OpenAIChatProvider,
)


class FakeProvider:
    def __init__(self, result=None, error=None):
        self.result = result or ChatResult("ok", "fake", "fake-model", "local", 1)
        self.error = error
        self.calls = []

    def chat(self, prompt):
        self.calls.append(prompt)
        if self.error:
            raise self.error
        return self.result


def test_local_mode_never_calls_online_provider():
    local, online = FakeProvider(), FakeProvider()
    result = ProviderRouter(local, online).chat("hello", "local")
    assert result.provider == "fake"
    assert local.calls == ["hello"]
    assert online.calls == []


def test_online_mode_never_falls_back_to_local():
    local = FakeProvider()
    online = FakeProvider(error=ProviderError("offline"))
    try:
        ProviderRouter(local, online).chat("hello", "online")
    except ProviderError:
        pass
    else:
        raise AssertionError("online-only mode must surface provider failure")
    assert local.calls == []


def test_hybrid_prefers_online():
    local, online = FakeProvider(), FakeProvider(
        ChatResult("cloud", "cloud", "model", "online", 2)
    )
    result = ProviderRouter(local, online).chat("hello", "hybrid")
    assert result.content == "cloud"
    assert result.mode == "hybrid"
    assert not result.fallback_used
    assert local.calls == []


def test_hybrid_falls_back_to_local_on_provider_error():
    local = FakeProvider(ChatResult("local", "ollama", "model", "local", 3))
    online = FakeProvider(error=ProviderError("unavailable"))
    result = ProviderRouter(local, online).chat("hello", "hybrid")
    assert result.content == "local"
    assert result.mode == "hybrid"
    assert result.fallback_used


def test_hybrid_reports_both_providers_unavailable():
    local = FakeProvider(error=ProviderError("local down"))
    online = FakeProvider(error=ProviderError("cloud down"))
    try:
        ProviderRouter(local, online).chat("hello", "hybrid")
    except ProviderError as exc:
        assert "local model is unavailable" in str(exc)
    else:
        raise AssertionError("expected safe provider failure")


def test_prompt_is_bounded_and_nonempty():
    router = ProviderRouter(FakeProvider(), FakeProvider())
    for prompt in ("", "  ", "x" * (MAX_PROMPT_CHARS + 1)):
        try:
            router.chat(prompt)
        except ValueError:
            pass
        else:
            raise AssertionError("invalid prompt accepted")


def test_unknown_mode_rejected():
    try:
        ProviderRouter(FakeProvider(), FakeProvider()).chat("hello", "automatic-magic")
    except ValueError:
        pass
    else:
        raise AssertionError("unknown provider mode accepted")


def test_ollama_is_loopback_only_and_does_not_accept_custom_url(monkeypatch):
    seen = {}
    def fake_open(url, payload, headers, timeout):
        seen.update(url=url, payload=payload, headers=headers, timeout=timeout)
        return {"message": {"content": "سلام"}}
    monkeypatch.setattr("el_agent.providers._open_json", fake_open)
    result = OllamaChatProvider(model="test").chat("سلام")
    assert seen["url"] == "http://127.0.0.1:11434/api/chat"
    assert seen["payload"]["stream"] is False
    assert result.content == "سلام"


def test_cloud_provider_uses_fixed_https_endpoint_and_keyring(monkeypatch):
    class FakeKeyring:
        def get_password(self, service, username):
            assert service == "MR_SIAVASH_EL_AGENT"
            assert username == "openai-api-key"
            return "secret-test-key"
    monkeypatch.setattr("el_agent.providers._keyring_module", lambda: FakeKeyring())
    seen = {}
    def fake_open(url, payload, headers, timeout):
        seen.update(url=url, headers=headers, payload=payload)
        return {"choices": [{"message": {"content": "Hello"}}]}
    monkeypatch.setattr("el_agent.providers._open_json", fake_open)
    result = OpenAIChatProvider(model="test-model").chat("hello")
    assert seen["url"] == "https://api.openai.com/v1/chat/completions"
    assert seen["headers"]["Authorization"] == "Bearer secret-test-key"
    assert result.content == "Hello"


def test_cloud_key_is_never_in_error_text(monkeypatch):
    class FakeKeyring:
        def get_password(self, service, username):
            return "secret-test-key"
    monkeypatch.setattr("el_agent.providers._keyring_module", lambda: FakeKeyring())
    def fail(*args, **kwargs):
        raise ProviderError("Provider returned HTTP 401.")
    monkeypatch.setattr("el_agent.providers._open_json", fail)
    try:
        OpenAIChatProvider(model="test-model").chat("hello")
    except ProviderError as exc:
        assert "secret-test-key" not in str(exc)
    else:
        raise AssertionError("expected provider failure")


def test_keyring_backend_fails_closed_for_plaintext_backend(monkeypatch):
    import sys
    from types import SimpleNamespace

    class PlaintextBackend:
        priority = 1

    fake = SimpleNamespace(get_keyring=lambda: PlaintextBackend())
    monkeypatch.setitem(sys.modules, "keyring", fake)
    try:
        from el_agent.providers import _keyring_module
        _keyring_module()
    except ProviderError as exc:
        assert "OS credential-store backend" in str(exc)
    else:
        raise AssertionError("plaintext backend must not be accepted")
