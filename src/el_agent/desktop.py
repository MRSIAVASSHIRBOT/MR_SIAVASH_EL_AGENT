"""Tk desktop control center for the authenticated local companion."""
from __future__ import annotations

import json
import threading
import time
import tkinter as tk
from datetime import datetime
from tkinter import messagebox, ttk
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from el_agent.intent import parse_local_intent
from el_agent.security import load_token
from el_agent.api import create_app
import uvicorn

API = "http://127.0.0.1:8765"
APP_ALIASES = ("notepad", "calculator", "explorer", "browser", "chatgpt")


def _request(method: str, path: str, payload: dict | None = None):
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = Request(API + path, data=body, method=method, headers={
        "Authorization": "Bearer " + load_token(),
        "Content-Type": "application/json",
    })
    try:
        with urlopen(request, timeout=8) as response:
            raw = response.read()
            return json.loads(raw.decode("utf-8")) if raw else {}
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Agent API returned HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError("Agent is not running. Start it with: el-agent serve") from exc


class DesktopControlCenter:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("MR_SIAVASH_EL_AGENT")
        root.geometry("760x590")
        root.minsize(640, 480)

        outer = ttk.Frame(root, padding=16)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="MR_SIAVASH_EL_AGENT", font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(outer, text="Local desktop control center · Loopback only").pack(anchor="w", pady=(0, 12))

        command_frame = ttk.LabelFrame(outer, text="فرمان سریع فارسی / English", padding=10)
        command_frame.pack(fill="x", pady=5)
        self.command = tk.StringVar()
        entry = ttk.Entry(command_frame, textvariable=self.command)
        entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        entry.bind("<Return>", lambda _event: self.run_command())
        ttk.Button(command_frame, text="اجرا", command=self.run_command).pack(side="right")

        action_frame = ttk.LabelFrame(outer, text="عملیات مجاز", padding=10)
        action_frame.pack(fill="x", pady=5)
        self.app = tk.StringVar(value="chatgpt")
        ttk.Combobox(action_frame, textvariable=self.app, values=APP_ALIASES, state="readonly", width=16).pack(side="left")
        ttk.Button(action_frame, text="بازکردن برنامه", command=self.launch_selected).pack(side="left", padx=6)
        ttk.Button(action_frame, text="گرفتن Screenshot", command=self.screenshot).pack(side="left")

        schedule_frame = ttk.LabelFrame(outer, text="زمان‌بندی اجرای برنامه (ISO 8601 همراه منطقه زمانی)", padding=10)
        schedule_frame.pack(fill="x", pady=5)
        self.schedule_app = tk.StringVar(value="chatgpt")
        self.run_at = tk.StringVar()
        ttk.Combobox(schedule_frame, textvariable=self.schedule_app, values=APP_ALIASES, state="readonly", width=14).pack(side="left")
        ttk.Entry(schedule_frame, textvariable=self.run_at).pack(side="left", fill="x", expand=True, padx=8)
        ttk.Button(schedule_frame, text="ثبت زمان‌بندی", command=self.schedule).pack(side="right")
        ttk.Label(schedule_frame, text="نمونه: 2026-11-21T14:00:00+03:30").pack(anchor="w", pady=(6, 0))

        tasks_frame = ttk.LabelFrame(outer, text="وظایف زمان‌بندی‌شده", padding=8)
        tasks_frame.pack(fill="both", expand=True, pady=5)
        columns = ("app", "run_at", "status", "task_id")
        self.tasks = ttk.Treeview(tasks_frame, columns=columns, show="headings", height=8)
        for key, label in (("app", "برنامه"), ("run_at", "زمان اجرا"), ("status", "وضعیت"), ("task_id", "شناسه")):
            self.tasks.heading(key, text=label)
        self.tasks.column("app", width=100)
        self.tasks.column("run_at", width=190)
        self.tasks.column("status", width=90)
        self.tasks.column("task_id", width=250)
        self.tasks.pack(fill="both", expand=True)
        controls = ttk.Frame(tasks_frame)
        controls.pack(fill="x", pady=(6, 0))
        ttk.Button(controls, text="به‌روزرسانی", command=self.refresh_tasks).pack(side="left")
        ttk.Button(controls, text="لغو وظیفه انتخاب‌شده", command=self.cancel_selected).pack(side="left", padx=6)

        self.status = tk.StringVar(value="در حال اتصال به Agent محلی…")
        ttk.Label(outer, textvariable=self.status, relief="sunken", anchor="w").pack(fill="x", pady=(8, 0))
        self.refresh_tasks()
        root.after(5000, self._poll_tasks)

    def _show_error(self, exc: Exception):
        self.status.set(str(exc))
        messagebox.showerror("MR_SIAVASH_EL_AGENT", str(exc), parent=self.root)

    def run_command(self):
        parsed = parse_local_intent(self.command.get())
        if parsed is None:
            self.status.set("فرمان شناخته نشد؛ فقط عملیات مجاز و مشخص پشتیبانی می‌شوند.")
            return
        try:
            if parsed["action"] == "screenshot":
                result = _request("POST", "/v1/screenshot")
                self.status.set("Screenshot ذخیره شد: " + result.get("local_path", result.get("filename", "")))
            else:
                alias = parsed["app_alias"]
                result = _request("POST", f"/v1/apps/{alias}/launch")
                self.status.set(result.get("detail", "برنامه اجرا شد."))
            self.command.set("")
        except Exception as exc:
            self._show_error(exc)

    def launch_selected(self):
        try:
            result = _request("POST", f"/v1/apps/{self.app.get()}/launch")
            self.status.set(result.get("detail", "برنامه اجرا شد."))
        except Exception as exc:
            self._show_error(exc)

    def screenshot(self):
        try:
            result = _request("POST", "/v1/screenshot")
            self.status.set("Screenshot ذخیره شد: " + result.get("local_path", result.get("filename", "")))
        except Exception as exc:
            self._show_error(exc)

    def schedule(self):
        try:
            run_at = datetime.fromisoformat(self.run_at.get())
            if run_at.tzinfo is None or run_at.utcoffset() is None:
                raise ValueError("زمان باید دارای UTC offset باشد.")
            result = _request("POST", "/v1/tasks", {
                "action": "launch_app", "app_alias": self.schedule_app.get(), "run_at": self.run_at.get()
            })
            self.status.set("زمان‌بندی ثبت شد: " + result["task_id"])
            self.refresh_tasks()
        except Exception as exc:
            self._show_error(exc)

    def refresh_tasks(self):
        try:
            response = _request("GET", "/v1/tasks")
            for item in self.tasks.get_children():
                self.tasks.delete(item)
            for task in response.get("tasks", []):
                self.tasks.insert("", "end", values=(
                    task.get("app_alias", ""), task.get("run_at", ""),
                    task.get("status", ""), task.get("task_id", "")
                ))
            self.status.set(f"Agent متصل است · {len(response.get('tasks', []))} وظیفه")
        except Exception as exc:
            self.status.set(str(exc))

    def cancel_selected(self):
        selected = self.tasks.selection()
        if not selected:
            self.status.set("ابتدا یک وظیفه را انتخاب کن.")
            return
        task_id = self.tasks.item(selected[0], "values")[3]
        try:
            _request("DELETE", f"/v1/tasks/{task_id}")
            self.status.set("وظیفه لغو شد.")
            self.refresh_tasks()
        except Exception as exc:
            self._show_error(exc)

    def _poll_tasks(self):
        self.refresh_tasks()
        self.root.after(15000, self._poll_tasks)


def main():
    load_token()
    server = None
    server_thread = None
    try:
        _request("GET", "/health")
    except Exception:
        config = uvicorn.Config(
            create_app(), host="127.0.0.1", port=8765,
            log_level="warning", access_log=False, server_header=False,
        )
        server = uvicorn.Server(config)
        server_thread = threading.Thread(target=server.run, name="el-agent-api", daemon=True)
        server_thread.start()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and not server.started:
            time.sleep(0.1)
        if not server.started:
            server.should_exit = True
            raise RuntimeError("Local Agent API did not start on 127.0.0.1:8765")

    root = tk.Tk()
    DesktopControlCenter(root)
    try:
        root.mainloop()
    finally:
        if server is not None:
            server.should_exit = True
        if server_thread is not None:
            server_thread.join(timeout=3)
