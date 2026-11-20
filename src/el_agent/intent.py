"""Small, deterministic Persian/English command grammar for safe local actions."""
from __future__ import annotations

import re
import unicodedata


APP_ALIASES = {
    "chatgpt": ("chatgpt", "chat gpt", "چت جی پی تی", "چت‌جی‌پی‌تی", "چت جی‌پی‌تی"),
    "notepad": ("notepad", "نوت پد", "نوت‌پد", "دفترچه یادداشت"),
    "calculator": ("calculator", "calc", "ماشین حساب", "ماشین‌حساب"),
    "explorer": ("explorer", "file explorer", "فایل اکسپلورر", "مدیریت فایل"),
    "browser": ("browser", "مرورگر", "مرورگر اینترنت"),
}


def _normalize(text: str) -> str:
    value = unicodedata.normalize("NFKC", text).lower()
    value = value.replace("\u200c", " ").replace("ي", "ی").replace("ك", "ک")
    value = re.sub(r"[!?؟،,.؛:]+", " ", value)
    value = " ".join(value.split())
    return value.removeprefix("لطفاً ").removeprefix("لطفا ").strip()


def parse_local_intent(text: str) -> dict[str, str] | None:
    """Return only a fixed, allowlisted local action; never parse shell commands."""
    normalized = _normalize(text)
    if not normalized or len(normalized) > 240:
        return None

    screenshot_commands = {
        "screenshot", "screen shot", "take screenshot", "take a screenshot",
        "capture screenshot", "اسکرین شات", "اسکرین شات بگیر",
        "عکس از صفحه", "عکس از صفحه بگیر", "از صفحه عکس بگیر",
    }
    if normalized in screenshot_commands:
        return {"action": "screenshot"}

    launch_aliases: list[str] = []
    for alias, phrases in APP_ALIASES.items():
        for phrase in {_normalize(item) for item in phrases}:
            accepted = {
                f"open {phrase}", f"launch {phrase}", f"start {phrase}",
                f"باز کن {phrase}", f"اجرا کن {phrase}", f"برو داخل {phrase}",
                f"{phrase} رو باز کن", f"{phrase} را باز کن",
                f"{phrase} باز کن", f"برو به {phrase}",
            }
            if normalized in accepted:
                launch_aliases.append(alias)
                break

    if len(launch_aliases) == 1:
        return {"action": "launch", "app_alias": launch_aliases[0]}
    return None
