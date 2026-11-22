from datetime import datetime,timedelta,timezone
from pathlib import Path
from fastapi.testclient import TestClient
from el_agent.api import create_app
from el_agent.store import TaskStore
TOKEN="test-token-that-is-long-enough-for-tests-123456789"

def client(tmp_path: Path):
    return TestClient(create_app(token=TOKEN,store=TaskStore(tmp_path/"tasks.db"),screenshots=tmp_path/"screens"))

def test_health_and_auth_boundary(tmp_path):
    c=client(tmp_path)
    assert c.get("/health").status_code==200
    assert c.get("/v1/device").status_code==401
    assert c.get("/v1/tasks").status_code==401
    assert c.get("/v1/device",headers={"Authorization":f"Bearer {TOKEN}"}).json()["remote_access"] is False

def test_schedule_requires_timezone_and_future_time(tmp_path):
    c=client(tmp_path); h={"Authorization":f"Bearer {TOKEN}"}
    assert c.post("/v1/tasks",headers=h,json={"action":"launch_app","app_alias":"notepad","run_at":"2030-01-01T12:00:00"}).status_code==422
    past=(datetime.now(timezone.utc)-timedelta(minutes=1)).isoformat()
    assert c.post("/v1/tasks",headers=h,json={"action":"launch_app","app_alias":"notepad","run_at":past}).status_code==422

def test_schedule_creation_and_cancel(tmp_path):
    c=client(tmp_path); h={"Authorization":f"Bearer {TOKEN}"}
    future=(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()
    response=c.post("/v1/tasks",headers=h,json={"action":"launch_app","app_alias":"notepad","run_at":future})
    assert response.status_code==201
    task_id=response.json()["task_id"]
    assert c.get("/v1/tasks",headers=h).json()["tasks"][0]["status"]=="pending"
    assert c.delete(f"/v1/tasks/{task_id}",headers=h).json()["status"]=="cancelled"

def test_unknown_fields_and_unlisted_alias_are_rejected(tmp_path):
    c=client(tmp_path); h={"Authorization":f"Bearer {TOKEN}"}
    future=(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()
    bad=c.post("/v1/tasks",headers=h,json={"action":"launch_app","app_alias":"notepad","run_at":future,"shell":"whoami"})
    assert bad.status_code==422
    assert c.post("/v1/tasks",headers=h,json={"action":"launch_app","app_alias":"powershell","run_at":future}).status_code==422

def test_chatgpt_is_an_explicitly_allowlisted_schedule_target(tmp_path):
    c=client(tmp_path); h={"Authorization":f"Bearer {TOKEN}"}
    future=(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()
    response=c.post("/v1/tasks",headers=h,json={"action":"launch_app","app_alias":"chatgpt","run_at":future})
    assert response.status_code==201
    assert response.json()["app_alias"]=="chatgpt"

def test_device_advertises_fixed_chatgpt_alias_and_local_capabilities(tmp_path):
    c=client(tmp_path); h={"Authorization":f"Bearer {TOKEN}"}
    response=c.get("/v1/device",headers=h)
    assert response.status_code==200
    body=response.json()
    assert "chatgpt" in body["supported_apps"]
    assert body["remote_access"] is False


def test_assistant_chat_requires_auth_and_returns_provider_metadata(tmp_path, monkeypatch):
    class FakeResult:
        content = "سلام"
        provider = "test-local"
        model = "test-model"
        mode = "local"
        latency_ms = 7
        fallback_used = False

    class FakeRouter:
        def chat(self, prompt, mode):
            assert prompt == "سلام"
            assert mode == "local"
            return FakeResult()

    monkeypatch.setattr("el_agent.api.ProviderRouter", FakeRouter)
    c = client(tmp_path)
    assert c.post("/v1/assistant/chat", json={"prompt": "سلام"}).status_code == 401
    headers = {"Authorization": f"Bearer {TOKEN}"}
    response = c.post("/v1/assistant/chat", headers=headers, json={"prompt": "سلام"})
    assert response.status_code == 200
    assert response.json()["provider"] == "test-local"
    assert response.json()["content"] == "سلام"


def test_assistant_chat_rejects_blank_oversized_and_unknown_fields(tmp_path):
    c = client(tmp_path)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    assert c.post("/v1/assistant/chat", headers=headers, json={"prompt": " "}).status_code == 422
    assert c.post("/v1/assistant/chat", headers=headers, json={"prompt": "x" * 8001}).status_code == 422
    assert c.post("/v1/assistant/chat", headers=headers, json={"prompt": "hi", "shell": "no"}).status_code == 422
    assert c.post("/v1/assistant/chat", headers=headers, json={"prompt": "hi", "mode": "magic"}).status_code == 422



def test_schedule_idempotency_returns_the_original_task(tmp_path):
    c = client(tmp_path)
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Idempotency-Key": "schedule-chatgpt-2026-unique",
    }
    future = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
    payload = {"action": "launch_app", "app_alias": "chatgpt", "run_at": future}

    first = c.post("/v1/tasks", headers=headers, json=payload)
    replay = c.post("/v1/tasks", headers=headers, json=payload)

    assert first.status_code == 201
    assert replay.status_code == 201
    assert replay.json()["task_id"] == first.json()["task_id"]
    assert len(c.get("/v1/tasks", headers=headers).json()["tasks"]) == 1


def test_schedule_idempotency_rejects_key_reuse_with_different_payload(tmp_path):
    c = client(tmp_path)
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Idempotency-Key": "schedule-key-conflict-2026",
    }
    future = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
    first = {"action": "launch_app", "app_alias": "notepad", "run_at": future}
    different = {"action": "launch_app", "app_alias": "calculator", "run_at": future}

    assert c.post("/v1/tasks", headers=headers, json=first).status_code == 201
    conflict = c.post("/v1/tasks", headers=headers, json=different)

    assert conflict.status_code == 409
    assert len(c.get("/v1/tasks", headers=headers).json()["tasks"]) == 1


def test_schedule_idempotency_key_is_bounded(tmp_path):
    c = client(tmp_path)
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Idempotency-Key": "x" * 129,
    }
    future = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
    payload = {"action": "launch_app", "app_alias": "notepad", "run_at": future}

    assert c.post("/v1/tasks", headers=headers, json=payload).status_code == 422
