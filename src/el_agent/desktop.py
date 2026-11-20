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
from el_agent.hub import hub_status
from el_agent.security import load_token
from el_agent.api import create_app
from el_agent.voice import get_voice_setup_status, listen_once, speak_text, VoiceUnavailable
import uvicorn

API = "http://127.0.0.1:8765"
APP_ALIASES = ("notepad", "calculator", "explorer", "browser", "chatgpt")


def _request(method: str, path: str, payload: dict | None = None, timeout: int = 8):
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = Request(API + path, data=body, method=method, headers={
        "Authorization": "Bearer " + load_token(),
        "Content-Type": "application/json",
    })
    try:
        with urlopen(request, timeout=timeout) as response:
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
        root.geometry("760x820")
        root.minsize(640, 680)

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

        voice_frame = ttk.LabelFrame(outer, text="فرمان صوتی (Push-to-Talk)", padding=10)
        voice_frame.pack(fill="x", pady=5)
        self.voice_status = tk.StringVar(value="میکروفون خاموش است.")
        ttk.Label(voice_frame, textvariable=self.voice_status).pack(side="left", fill="x", expand=True)
        self.voice_start_button = ttk.Button(voice_frame, text="شروع ضبط صدا", command=self.start_voice)
        self.voice_start_button.pack(side="left", padx=4)
        self.voice_stop_button = ttk.Button(voice_frame, text="پایان ضبط", command=self.stop_voice, state="disabled")
        self.voice_stop_button.pack(side="left")
        self._voice_stop_event = None
        self._voice_thread = None

        chat_frame = ttk.LabelFrame(outer, text="دستیار هوشمند متنی · Local / Online / Hybrid", padding=10)
        chat_frame.pack(fill="x", pady=5)
        chat_controls = ttk.Frame(chat_frame)
        chat_controls.pack(fill="x")
        self.chat_mode = tk.StringVar(value="local")
        ttk.Combobox(chat_controls, textvariable=self.chat_mode, values=("local", "online", "hybrid"),
                     state="readonly", width=10).pack(side="left")
        self.chat_prompt = tk.StringVar()
        chat_entry = ttk.Entry(chat_controls, textvariable=self.chat_prompt)
        chat_entry.pack(side="left", fill="x", expand=True, padx=8)
        chat_entry.bind("<Return>", lambda _event: self.send_chat())
        self.chat_send_button = ttk.Button(chat_controls, text="ارسال", command=self.send_chat)
        self.chat_send_button.pack(side="right")
        self.chat_output = tk.Text(chat_frame, height=5, wrap="word", state="disabled")
        self.chat_output.pack(fill="x", pady=(8, 0))
        ttk.Label(chat_frame, text="Local پیش‌فرض است؛ Online و Hybrid فقط با انتخاب خودت فعال می‌شوند.").pack(anchor="w")

        action_frame = ttk.LabelFrame(outer, text="عملیات مجاز", padding=10)
        action_frame.pack(fill="x", pady=5)
        self.app = tk.StringVar(value="chatgpt")
        ttk.Combobox(action_frame, textvariable=self.app, values=APP_ALIASES, state="readonly", width=16).pack(side="left")
        ttk.Button(action_frame, text="بازکردن برنامه", command=self.launch_selected).pack(side="left", padx=6)
        ttk.Button(action_frame, text="گرفتن Screenshot", command=self.screenshot).pack(side="left")
        ttk.Button(action_frame, text="وضعیت اتصال Hub", command=self.show_hub_status).pack(side="left", padx=6)

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

        root.protocol("WM_DELETE_WINDOW", self.close)

        self.status = tk.StringVar(value="در حال اتصال به Agent محلی…")
        ttk.Label(outer, textvariable=self.status, relief="sunken", anchor="w").pack(fill="x", pady=(8, 0))
        self.refresh_tasks()
        root.after(5000, self._poll_tasks)

    def send_chat(self):
        prompt = self.chat_prompt.get().strip()
        if not prompt:
            self.status.set("ابتدا پیام متنی را وارد کن.")
            return
        mode = self.chat_mode.get()
        self.chat_prompt.set("")
        self.chat_send_button.configure(state="disabled")
        self._append_chat("شما: " + prompt + "\n")
        self.status.set("در حال دریافت پاسخ از مسیر " + mode + "…")

        def worker():
            try:
                result = _request("POST", "/v1/assistant/chat",
                                  {"prompt": prompt, "mode": mode}, timeout=240)
                answer = result.get("content", "")
                provider = result.get("provider", "unknown")
                fallback = " · بازگشت به مدل محلی" if result.get("fallback_used") else ""
                message = f"Agent ({provider}){fallback}: {answer}\n\n"
                status = "پاسخ دریافت شد · " + str(result.get("latency_ms", "?")) + " ms"
            except Exception as exc:
                message = "خطا: " + str(exc) + "\n\n"
                status = "دریافت پاسخ ناموفق بود؛ هیچ عملیات سیستمی اجرا نشد."
            try:
                self.root.after(0, lambda: self._finish_chat(message, status))
            except tk.TclError:
                pass

        threading.Thread(target=worker, name="el-agent-chat", daemon=True).start()

    def _append_chat(self, message: str):
        self.chat_output.configure(state="normal")
        self.chat_output.insert("end", message)
        current = self.chat_output.get("1.0", "end-1c")
        if len(current) > 100_000:
            self.chat_output.delete("1.0", f"1.0 + {len(current) - 100_000} chars")
        self.chat_output.see("end")
        self.chat_output.configure(state="disabled")

    def _finish_chat(self, message: str, status: str):
        self._append_chat(message)
        self.chat_send_button.configure(state="normal")
        self.status.set(status)

    def start_voice(self):
        if self._voice_thread is not None and self._voice_thread.is_alive():
            return
        setup = get_voice_setup_status()
        if not setup["ready"]:
            missing = ", ".join(setup["missing_packages"]) or "none"
            message = (
                "برای فعال‌سازی گفتار محلی ابتدا وابستگی‌های اختیاری و مدل فارسی Vosk را نصب کن.\n"
                "Missing packages: " + missing + "\n"
                "Model configured: " + str(setup["model_configured"]) + "\n"
                'Install: python -m pip install -e ".[voice]"\n'
                "Then set EL_AGENT_VOSK_MODEL to the extracted model directory."
            )
            self.voice_status.set("تنظیمات گفتار کامل نیست؛ میکروفون فعال نشد.")
            messagebox.showwarning("راه‌اندازی فرمان صوتی", message, parent=self.root)
            return

        stop_event = threading.Event()
        self._voice_stop_event = stop_event
        self.voice_start_button.configure(state="disabled")
        self.voice_stop_button.configure(state="normal")
        self.voice_status.set("میکروفون فعال است؛ صحبت کن و سپس «پایان ضبط» را بزن.")

        def worker():
            try:
                transcript = listen_once(stop_event, max_seconds=15)
            except Exception as exc:
                message = str(exc)
                self.root.after(0, lambda value=message: self._voice_error(value))
                return
            self.root.after(0, lambda value=transcript: self._handle_voice_transcript(value))

        self._voice_thread = threading.Thread(target=worker, name="el-agent-push-to-talk", daemon=True)
        self._voice_thread.start()

    def stop_voice(self):
        if self._voice_stop_event is not None:
            self._voice_stop_event.set()
            self.voice_status.set("در حال پایان‌دادن به ضبط…")

    def _voice_finished(self):
        self._voice_stop_event = None
        self.voice_start_button.configure(state="normal")
        self.voice_stop_button.configure(state="disabled")

    def _voice_error(self, message: str):
        self._voice_finished()
        self.voice_status.set("فرمان صوتی انجام نشد: " + message)
        self.status.set("فرمان صوتی انجام نشد؛ هیچ عملیاتی اجرا نشد.")

    def _handle_voice_transcript(self, transcript: str):
        self._voice_finished()
        self.command.set(transcript)
        self.voice_status.set("متن تشخیص‌داده‌شده: " + transcript)
        parsed = parse_local_intent(transcript)
        if parsed is None:
            self.status.set("فرمان شنیده شد اما در فهرست مجاز نیست؛ هیچ عملیاتی اجرا نشد.")
            return
        self._execute_intent(parsed, spoken=True)

    def _speak_reply(self, reply: str):
        try:
            speak_text(reply)
            self.root.after(0, lambda: self.voice_status.set("پاسخ صوتی پخش شد."))
        except VoiceUnavailable:
            self.root.after(0, lambda: self.voice_status.set("پاسخ متنی آماده است؛ صدای فارسی سیستم در دسترس نیست."))

    def _execute_intent(self, parsed: dict[str, str], *, spoken: bool = False):
        try:
            if parsed["action"] == "screenshot":
                result = _request("POST", "/v1/screenshot")
                reply = "اسکرین‌شات به‌صورت محلی ذخیره شد."
                self.status.set("Screenshot ذخیره شد: " + result.get("local_path", result.get("filename", "")))
            else:
                alias = parsed["app_alias"]
                result = _request("POST", f"/v1/apps/{alias}/launch")
                reply = result.get("detail", "برنامه اجرا شد.")
                self.status.set(reply)
            if not spoken:
                self.command.set("")
            else:
                threading.Thread(target=self._speak_reply, args=(reply,), name="el-agent-tts", daemon=True).start()
        except Exception as exc:
            self._show_error(exc)

    def close(self):
        if self._voice_stop_event is not None:
            self._voice_stop_event.set()
        self.root.destroy()

    def _show_error(self, exc: Exception):
        self.status.set(str(exc))
        messagebox.showerror("MR_SIAVASH_EL_AGENT", str(exc), parent=self.root)

    def run_command(self):
        parsed = parse_local_intent(self.command.get())
        if parsed is None:
            self.status.set("فرمان شناخته نشد؛ فقط عملیات مجاز و مشخص پشتیبانی می‌شوند.")
            return
        self._execute_intent(parsed)

    def launch_selected(self):
        try:
            result = _request("POST", f"/v1/apps/{self.app.get()}/launch")
            self.status.set(result.get("detail", "برنامه اجرا شد."))
        except Exception as exc:
            self._show_error(exc)

    def show_hub_status(self):
        try:
            result = hub_status()
            if result.get("paired"):
                self.status.set("Hub متصل · Scope: " + ", ".join(result.get("scopes", [])))
            else:
                self.status.set("Hub هنوز Pair نشده است · " + str(result.get("device_id", "")))
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
