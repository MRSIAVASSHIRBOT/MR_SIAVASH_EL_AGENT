"""Authenticated loopback-only API for the Windows companion."""
from __future__ import annotations
import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal
from uuid import UUID
from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator
from el_agent.actions import APP_ALLOWLIST, capture_screenshot, launch_app
from el_agent.security import load_token, token_matches
from el_agent.service import screenshot_dir, task_store, scheduler_loop, hub_heartbeat_loop, hub_task_loop
from el_agent.store import TaskStore
from el_agent.providers import ProviderError, ProviderRouter

class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    prompt: str = Field(min_length=1, max_length=8000)
    mode: Literal["local", "online", "hybrid"] = "local"

    @field_validator("prompt")
    @classmethod
    def validate_prompt(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("prompt must not be blank")
        return value.strip()


class ScheduleRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action: Literal["launch_app"]
    app_alias: Literal["notepad","calculator","explorer","browser","chatgpt"]
    run_at: datetime
    @field_validator("run_at")
    @classmethod
    def validate_time(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("run_at must include a timezone offset")
        value = value.astimezone(timezone.utc)
        now = datetime.now(timezone.utc)
        if value <= now or value > now + timedelta(days=30):
            raise ValueError("run_at must be in the next 30 days")
        return value

def create_app(*, token: str | None = None, store: TaskStore | None = None, screenshots: Path | None = None) -> FastAPI:
    expected = token if token is not None else load_token()
    db = store if store is not None else task_store()
    output = screenshots if screenshots is not None else screenshot_dir()
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        worker = asyncio.create_task(scheduler_loop(db))
        hub_worker = asyncio.create_task(hub_heartbeat_loop())
        task_worker = asyncio.create_task(hub_task_loop())
        try:
            yield
        finally:
            worker.cancel()
            hub_worker.cancel()
            task_worker.cancel()
            for task in (worker, hub_worker, task_worker):
                try:
                    await task
                except asyncio.CancelledError:
                    pass
    app = FastAPI(title="MR_SIAVASH_EL_AGENT", version="0.2.0", description="Local Windows companion; loopback-only.")
    app.router.lifespan_context = lifespan
    async def auth(authorization: str | None = Header(default=None)) -> None:
        scheme, _, candidate = (authorization or "").partition(" ")
        if scheme.lower() != "bearer" or not token_matches(candidate, expected):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or missing bearer token")
    @app.get("/health")
    async def health():
        return {"status":"ok","service":"MR_SIAVASH_EL_AGENT","version":"0.2.0"}
    @app.get("/v1/device")
    async def device(_: None = Depends(auth)):
        return {"platform":"windows","mode":"local","remote_access":False,
                "supported_apps":sorted([*APP_ALLOWLIST,"browser","chatgpt"]),
                "capabilities":["device.heartbeat","screen.capture.explicit","app.launch.allowlisted","task.schedule.launch","assistant.chat.local","assistant.chat.online.opt_in","assistant.chat.hybrid.opt_in"]}
    @app.post("/v1/assistant/chat")
    async def assistant_chat(payload: ChatRequest, _: None = Depends(auth)):
        try:
            result = await asyncio.to_thread(ProviderRouter().chat, payload.prompt, payload.mode)
            return {
                "content": result.content,
                "provider": result.provider,
                "model": result.model,
                "mode": result.mode,
                "latency_ms": result.latency_ms,
                "fallback_used": result.fallback_used,
            }
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except ProviderError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

    @app.post("/v1/screenshot",status_code=201)
    async def screenshot(_: None = Depends(auth)):
        try: return capture_screenshot(output)
        except RuntimeError as exc: raise HTTPException(status_code=501,detail=str(exc)) from exc
        except Exception as exc: raise HTTPException(status_code=500,detail="Screenshot capture failed") from exc
    @app.get("/v1/screenshots/{filename}")
    async def get_screenshot(filename: str, _: None = Depends(auth)):
        if not filename.startswith("screen-") or not filename.endswith(".png") or Path(filename).name != filename:
            raise HTTPException(status_code=404,detail="Screenshot not found")
        target = output / filename
        if not target.is_file(): raise HTTPException(status_code=404,detail="Screenshot not found")
        return FileResponse(target,media_type="image/png",filename=filename)
    @app.post("/v1/apps/{alias}/launch")
    async def launch(alias: str, _: None = Depends(auth)):
        try: return launch_app(alias)
        except ValueError as exc: raise HTTPException(status_code=404,detail="Application alias is not allowlisted") from exc
        except RuntimeError as exc: raise HTTPException(status_code=501,detail=str(exc)) from exc
    @app.post("/v1/tasks",status_code=201)
    async def create_task(payload: ScheduleRequest, _: None = Depends(auth)):
        return db.create_launch(payload.run_at,payload.app_alias)
    @app.get("/v1/tasks")
    async def list_tasks(_: None = Depends(auth)):
        return {"tasks":db.list_tasks()}
    @app.delete("/v1/tasks/{task_id}")
    async def cancel_task(task_id: UUID, _: None = Depends(auth)):
        if not db.cancel(str(task_id)): raise HTTPException(status_code=409,detail="Task is not pending or does not exist")
        return {"task_id":str(task_id),"status":"cancelled"}
    return app
