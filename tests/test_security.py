from pathlib import Path
import os
import stat

import pytest

from el_agent.security import create_token, load_token, token_matches


def test_token_is_created_once_and_loaded(tmp_path: Path):
    path = tmp_path / "secrets" / "api.token"
    token = create_token(path)
    assert load_token(path) == token
    with pytest.raises(FileExistsError):
        create_token(path)


def test_created_token_file_is_private_on_posix(tmp_path: Path):
    path = tmp_path / "api.token"
    create_token(path)
    if os.name != "nt":
        assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.parametrize(
    "contents",
    [
        "",
        "short",
        "x" * 129,
        "x" * 42 + "!",
        "token with spaces and padding",
        "\\x" + "a" * 42,
    ],
)
def test_load_token_rejects_malformed_or_oversized_files(tmp_path: Path, contents: str):
    path = tmp_path / "api.token"
    path.write_text(contents, encoding="utf-8")
    with pytest.raises(RuntimeError, match="invalid"):
        load_token(path)


def test_load_token_rejects_non_regular_files(tmp_path: Path):
    path = tmp_path / "token-directory"
    path.mkdir()
    with pytest.raises(RuntimeError, match="regular file"):
        load_token(path)


def test_load_token_does_not_follow_symlinks_when_supported(tmp_path: Path):
    target = tmp_path / "real-token"
    token = "A" * 43
    target.write_text(token, encoding="ascii")
    link = tmp_path / "api.token"
    try:
        link.symlink_to(target)
    except (NotImplementedError, OSError):
        pytest.skip("Symlink creation is unavailable on this platform.")

    with pytest.raises(RuntimeError, match="safely read"):
        load_token(link)


def test_constant_time_comparison_contract():
    assert token_matches("same", "same")
    assert not token_matches(None, "same")
    assert not token_matches("wrong", "same")
