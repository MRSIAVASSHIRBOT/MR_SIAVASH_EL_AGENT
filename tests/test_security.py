from pathlib import Path
import pytest
from el_agent.security import create_token,load_token,token_matches

def test_token_is_created_once_and_loaded(tmp_path: Path):
    path=tmp_path/"secrets"/"api.token"
    token=create_token(path)
    assert load_token(path)==token
    with pytest.raises(FileExistsError): create_token(path)

def test_constant_time_comparison_contract():
    assert token_matches("same","same")
    assert not token_matches(None,"same")
    assert not token_matches("wrong","same")
