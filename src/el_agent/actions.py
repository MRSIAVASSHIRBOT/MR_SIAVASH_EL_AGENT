"""Allowlisted local Windows actions. No shell or caller-provided commands."""
from __future__ import annotations
import platform
import subprocess
import webbrowser
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

APP_ALLOWLIST = {"notepad": ("notepad.exe",), "calculator": ("calc.exe",), "explorer": ("explorer.exe",)}

def launch_app(alias: str) -> dict:
    if platform.system() != "Windows":
        raise RuntimeError("Application launch is supported only on Windows.")
    if alias == "browser":
        if not webbrowser.open("about:blank", new=2):
            raise RuntimeError("Default browser could not be opened.")
        return {"alias": alias, "started": True, "detail": "Opened default browser."}
    command = APP_ALLOWLIST.get(alias)
    if command is None:
        raise ValueError("Unsupported application alias.")
    subprocess.Popen(list(command), shell=False, close_fds=True)
    return {"alias": alias, "started": True, "detail": f"Started {alias}."}

def capture_screenshot(output_dir: Path) -> dict:
    if platform.system() != "Windows":
        raise RuntimeError("Screenshot capture is supported only on Windows.")
    from mss import mss
    from PIL import Image
    output_dir.mkdir(parents=True, exist_ok=True)
    with mss() as capture:
        monitor = capture.monitors[1]
        shot = capture.grab(monitor)
        image = Image.frombytes("RGB", shot.size, shot.rgb)
    filename = f"screen-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid4().hex[:8]}.png"
    destination = output_dir / filename
    image.save(destination, format="PNG", optimize=True)
    return {"filename": filename, "width": image.width, "height": image.height, "captured_at": datetime.now(timezone.utc).isoformat(), "local_path": str(destination)}
