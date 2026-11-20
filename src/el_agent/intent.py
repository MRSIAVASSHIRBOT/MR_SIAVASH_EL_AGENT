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
    return " ".join(value.split())


def parse_local_intent(text: str) -> dict[str, str] | None:
    """Return only a fixed, allowlisted local action; never parse shell commands."""
    normalized = _normalize(text)
    if not normalized or len(normalized) > 240:
        return None

    screenshot_terms = ("screenshot", "screen shot", "اسکرین شات", "عکس از صفحه")
    capture_terms = ("capture", "take", "بگیر", "ثبت کن", "تهیه کن")
    if any(term in normalized for term in screenshot_terms):
        if any(term in normalized for term in capture_terms) or normalized in screenshot_terms:
            return {"action": "screenshot"}

    launch_terms = ("open", "launch", "start", "باز کن", "اجرا کن", "برو داخل", "بازش کن")
    if any(term in normalized for term in launch_terms):
        for alias, phrases in APP_ALIASES.items():
            if any(_normalize(phrase) in normalized for phrase in phrases):
                return {"action": "launch", "app_alias": alias}
    return None
