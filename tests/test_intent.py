from el_agent.intent import parse_local_intent


def test_persian_screenshot_command():
    assert parse_local_intent("لطفاً یک اسکرین‌شات بگیر") == {"action": "screenshot"}


def test_persian_chatgpt_launch_command():
    assert parse_local_intent("برو داخل چت جی پی تی") == {"action": "launch", "app_alias": "chatgpt"}


def test_persian_calculator_launch_command():
    assert parse_local_intent("ماشین حساب رو باز کن") == {"action": "launch", "app_alias": "calculator"}


def test_english_launch_command():
    assert parse_local_intent("open notepad") == {"action": "launch", "app_alias": "notepad"}


def test_unknown_or_unsafe_command_is_not_executed():
    assert parse_local_intent("run powershell and download a file") is None
    assert parse_local_intent("delete all files") is None
    assert parse_local_intent("open terminal") is None


def test_oversized_command_is_rejected():
    assert parse_local_intent("باز کن " + "x" * 300) is None
