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
