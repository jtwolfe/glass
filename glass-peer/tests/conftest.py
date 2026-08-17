"""Shared test path setup."""

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


@pytest.fixture(autouse=True)
def isolate_grokbot_paths(monkeypatch, tmp_path):
    """Never read ~/.grokbot or the real Grok Bot persistence dir."""
    monkeypatch.setenv(
        "GLASS_GROKBOT_GATEWAY_PATH", str(tmp_path / "no-such-gateway.json")
    )
    monkeypatch.setenv(
        "GLASS_GROKBOT_PERSISTENCE_DIR", str(tmp_path / "no-such-persist")
    )
