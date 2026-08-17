"""Unit tests for the this-session reply log."""

import json
import os
import uuid

import pytest

from session_log import SessionLog, _dumps
from state import StateStore


def _payload(text: str = "hi", **extra) -> dict:
    row = {
        "id": str(uuid.uuid4()),
        "from": "Ashleigh",
        "agentId": "agent-1",
        "text": text,
        "at": "2026-08-17T12:00:00.000+00:00",
        "inReplyTo": "echo-1",
    }
    row.update(extra)
    return row


def test_allocate_assigns_seq_and_persists(tmp_path):
    state = StateStore(str(tmp_path))
    log = SessionLog(state)
    first = log.allocate_and_append(_payload("I'll check."))
    second = log.allocate_and_append(_payload("Here's the report."))
    assert first is not None and second is not None
    assert [first["seq"], second["seq"]] == [0, 1]
    assert state.get_reply_seq() == 2
    raw = log.path.read_text(encoding="utf-8")
    assert raw == _dumps(json.loads(raw))
    data = json.loads(raw)
    assert data["sessionId"] == state.get_session_id()
    assert [r["seq"] for r in data["replies"]] == [0, 1]
    assert "I'll check." in raw
    assert log.after(0)[0]["text"] == "Here's the report."
    assert log.after(1) == []


def test_allocate_persist_failure_leaves_memory_and_seq(tmp_path, monkeypatch):
    state = StateStore(str(tmp_path))
    log = SessionLog(state)
    first = log.allocate_and_append(_payload("ok"))
    assert first is not None
    assert state.get_reply_seq() == 1

    def boom(_src, _dst):
        raise OSError("disk full")

    monkeypatch.setattr(os, "replace", boom)
    with pytest.raises(OSError, match="disk full"):
        log.allocate_and_append(_payload("fail"))
    rows = log.after(-1)
    assert [r["seq"] for r in rows] == [0]
    assert rows[0]["text"] == "ok"
    assert state.get_reply_seq() == 1


def test_cap_evicts_oldest(tmp_path):
    state = StateStore(str(tmp_path))
    log = SessionLog(state, max_replies=2, max_bytes=262144)
    for i in range(3):
        assert log.allocate_and_append(_payload(f"t{i}")) is not None
    rows = log.after(-1)
    assert [r["seq"] for r in rows] == [1, 2]
    assert [r["text"] for r in rows] == ["t1", "t2"]
    assert state.get_reply_seq() == 3


def test_byte_cap_evicts_then_truncates(tmp_path):
    state = StateStore(str(tmp_path))
    log = SessionLog(state, max_replies=10, max_bytes=280)
    small = log.allocate_and_append(_payload("tiny"))
    assert small is not None
    huge = log.allocate_and_append(_payload("x" * 4000))
    assert huge is not None
    rows = log.after(-1)
    assert all(r["seq"] != 0 for r in rows)
    assert huge["text"].endswith("…")
    assert len(huge["text"]) < 4000
    dumped = log.path.read_text(encoding="utf-8")
    assert len(dumped) <= 280
    assert log.path.exists()


def test_oversize_minimum_row_rejected(tmp_path):
    state = StateStore(str(tmp_path))
    log = SessionLog(state, max_replies=10, max_bytes=8)
    assert log.allocate_and_append(_payload("nope")) is None
    assert state.get_reply_seq() == 0
    assert log.after(-1) == []
    assert not log.path.exists()


def test_bind_repairs_holes_and_does_not_reuse(tmp_path):
    state = StateStore(str(tmp_path))
    sid = state.ensure_session_id()
    state.set_reply_seq(2)
    log_path = tmp_path / "session-log.json"
    log_path.write_text(
        json.dumps(
            {
                "sessionId": sid,
                "replies": [
                    {
                        "seq": 0,
                        "id": "a",
                        "from": "A",
                        "agentId": "a",
                        "text": "0",
                        "at": "t",
                        "inReplyTo": "e",
                    },
                    {
                        "seq": 1,
                        "id": "b",
                        "from": "A",
                        "agentId": "a",
                        "text": "1",
                        "at": "t",
                        "inReplyTo": "e",
                    },
                    {
                        "seq": 3,
                        "id": "d",
                        "from": "A",
                        "agentId": "a",
                        "text": "3",
                        "at": "t",
                        "inReplyTo": "e",
                    },
                ],
            }
        ),
        encoding="utf-8",
    )
    log = SessionLog(state)
    assert state.get_reply_seq() == 4
    assert [r["seq"] for r in log.after(-1)] == [0, 1, 3]
    nxt = log.allocate_and_append(_payload("next"))
    assert nxt is not None
    assert nxt["seq"] == 4
    assert [r["seq"] for r in log.after(-1)] == [0, 1, 3, 4]


def test_bind_discards_other_session_file(tmp_path):
    state = StateStore(str(tmp_path))
    state.ensure_session_id()
    state.set_reply_seq(7)
    (tmp_path / "session-log.json").write_text(
        json.dumps(
            {
                "sessionId": "other-session",
                "replies": [
                    {
                        "seq": 0,
                        "id": "x",
                        "from": "A",
                        "agentId": "a",
                        "text": "old",
                        "at": "t",
                        "inReplyTo": "e",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    log = SessionLog(state)
    assert log.after(-1) == []
    assert state.get_reply_seq() == 7
    assert not log.path.exists()


def test_clear_removes_file(tmp_path):
    state = StateStore(str(tmp_path))
    log = SessionLog(state)
    log.allocate_and_append(_payload("bye"))
    assert log.path.exists()
    log.clear()
    assert log.after(-1) == []
    assert not log.path.exists()


def test_set_invite_wipes_via_hook(tmp_path):
    from mint import mint_invite

    state = StateStore(str(tmp_path))
    log = SessionLog(state)
    state.bind_session_log(log.clear)
    log.allocate_and_append(_payload("old"))
    assert log.after(-1)
    state.set_invite(mint_invite(300))
    assert state.get_reply_seq() == 0
    assert log.after(-1) == []
    assert not log.path.exists()


def test_config_log_caps(monkeypatch, tmp_path):
    from config import Config

    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_SESSION_LOG_MAX", "12")
    monkeypatch.setenv("GLASS_SESSION_LOG_MAX_BYTES", "4096")
    cfg = Config.from_env()
    assert cfg.session_log_max == 12
    assert cfg.session_log_max_bytes == 4096
