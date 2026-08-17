"""ACP v1 stdio client: permission results, kick, respawn."""

from __future__ import annotations

import asyncio
import json
import os
import shlex
import sys
import uuid
from pathlib import Path

import pytest

from acp_client import AcpClient, permission_result
from agent import AcpBackend, AgentUnavailable, select_backend
from config import Config
from protocol import ProtocolHandler
from state import StateStore

PHONE = "a" * 52

FAKE_ACP = r'''
"""Fake ACP v1 agent: initialize/new/prompt + mid-prompt request_permission."""
import json
import os
import sys

def send(obj):
    sys.stdout.write(json.dumps(obj, separators=(",", ":")) + "\n")
    sys.stdout.flush()

def recv():
    line = sys.stdin.readline()
    if not line:
        return None
    return json.loads(line)

def append_json(path, obj):
    if not path:
        return
    with open(path, "a") as f:
        f.write(json.dumps(obj) + "\n")

def touch(path):
    if path:
        with open(path, "w") as f:
            f.write("1")

DEFAULT_OPTIONS = [
    {"optionId": "allow", "name": "Looks like allow", "kind": "reject_once"},
    {"optionId": "keep-forever", "name": "Always", "kind": "allow_always"},
    {"optionId": "once", "name": "Once", "kind": "allow_once"},
]

def main():
    side = os.environ.get("FAKE_ACP_SIDE")
    ready = os.environ.get("FAKE_ACP_READY")
    trace = os.environ.get("FAKE_ACP_TRACE")
    perm_path = os.environ.get("FAKE_ACP_PERM")
    fs_path = os.environ.get("FAKE_ACP_FS")
    text = os.environ.get("FAKE_ACP_TEXT", "scripted")
    pre = os.environ.get("FAKE_ACP_PRE", "")
    raw_opts = os.environ.get("FAKE_ACP_OPTIONS")
    options = json.loads(raw_opts) if raw_opts else DEFAULT_OPTIONS
    die_after = os.environ.get("FAKE_ACP_DIE_AFTER") == "1"
    send_fs = os.environ.get("FAKE_ACP_SEND_FS") == "1"
    empty = os.environ.get("FAKE_ACP_EMPTY") == "1"
    hang = os.environ.get("FAKE_ACP_HANG") == "1"
    hang_prompt = os.environ.get("FAKE_ACP_HANG_PROMPT") == "1"
    parse_once = os.environ.get("FAKE_ACP_PARSE_ONCE") == "1"
    bad_id = os.environ.get("FAKE_ACP_BAD_ID") == "1"
    session_id = "sess_fake"
    next_out = 9000
    pending_perm = None
    pending_fs = None
    prompt_id = None
    cancelled = False

    while True:
        msg = recv()
        if msg is None:
            break
        append_json(trace, msg)
        method = msg.get("method")
        mid = msg.get("id")

        if method == "initialize":
            send({
                "jsonrpc": "2.0",
                "id": mid,
                "result": {
                    "protocolVersion": 1,
                    "agentCapabilities": {"promptCapabilities": {}},
                    "agentInfo": {"name": "fake", "version": "0"},
                    "authMethods": [],
                },
            })
            if send_fs:
                next_out += 1
                pending_fs = next_out
                send({
                    "jsonrpc": "2.0",
                    "id": pending_fs,
                    "method": "fs/read_text_file",
                    "params": {"path": "/tmp/x"},
                })
            if bad_id:
                send({"jsonrpc": "2.0", "id": [1], "result": {}})
                send({
                    "jsonrpc": "2.0",
                    "id": {"x": 1},
                    "method": "session/request_permission",
                    "params": {"options": options},
                })
            continue

        if method == "session/new":
            send({"jsonrpc": "2.0", "id": mid, "result": {"sessionId": session_id}})
            continue

        if method == "session/prompt":
            if parse_once:
                parse_once = False
                send({
                    "jsonrpc": "2.0",
                    "id": mid,
                    "error": {"code": -32602, "message": "invalid params"},
                })
                continue
            if hang_prompt:
                prompt_id = mid
                continue
            prompt_id = mid
            if not empty:
                send({
                    "jsonrpc": "2.0",
                    "method": "session/update",
                    "params": {
                        "sessionId": session_id,
                        "update": {
                            "sessionUpdate": "agent_message_chunk",
                            "content": {"type": "text", "text": pre},
                        },
                    },
                })
            send({
                "jsonrpc": "2.0",
                "method": "session/update",
                "params": {
                    "sessionId": session_id,
                    "update": {
                        "sessionUpdate": "agent_thought_chunk",
                        "content": {"type": "text", "text": "secret-thought"},
                    },
                },
            })
            send({
                "jsonrpc": "2.0",
                "method": "session/update",
                "params": {
                    "sessionId": session_id,
                    "update": {
                        "sessionUpdate": "tool_call",
                        "toolCallId": "t1",
                        "title": "tool",
                        "kind": "other",
                    },
                },
            })
            next_out += 1
            pending_perm = next_out
            send({
                "jsonrpc": "2.0",
                "id": pending_perm,
                "method": "session/request_permission",
                "params": {
                    "sessionId": session_id,
                    "toolCall": {"toolCallId": "t1"},
                    "options": options,
                },
            })
            touch(ready)
            continue

        if method == "session/cancel":
            cancelled = True
            if prompt_id is not None:
                send({
                    "jsonrpc": "2.0",
                    "id": prompt_id,
                    "result": {"stopReason": "cancelled"},
                })
                prompt_id = None
            continue

        if mid == pending_fs and "error" in msg:
            append_json(fs_path, msg["error"])
            pending_fs = None
            continue

        if mid == pending_perm and "result" in msg:
            append_json(perm_path, msg["result"])
            pending_perm = None
            if hang:
                continue
            if cancelled:
                continue
            outcome = msg["result"].get("outcome") if isinstance(msg["result"], dict) else None
            if not isinstance(outcome, dict):
                outcome = {}
            selected = outcome.get("outcome") == "selected"
            option_id = outcome.get("optionId") if selected else None
            kind = None
            for opt in options:
                if opt.get("optionId") == option_id:
                    kind = opt.get("kind")
                    break
            allowed = selected and isinstance(kind, str) and kind.startswith("allow_")
            if allowed:
                if side:
                    touch(side)
                if not empty:
                    send({
                        "jsonrpc": "2.0",
                        "method": "session/update",
                        "params": {
                            "sessionId": session_id,
                            "update": {
                                "sessionUpdate": "agent_message_chunk",
                                "content": {"type": "text", "text": text},
                            },
                        },
                    })
                if prompt_id is not None:
                    send({
                        "jsonrpc": "2.0",
                        "id": prompt_id,
                        "result": {"stopReason": "end_turn"},
                    })
                    prompt_id = None
            else:
                if prompt_id is not None:
                    stop = (
                        "cancelled"
                        if outcome.get("outcome") == "cancelled"
                        else "end_turn"
                    )
                    send({
                        "jsonrpc": "2.0",
                        "id": prompt_id,
                        "result": {"stopReason": stop},
                    })
                    prompt_id = None
            if die_after:
                break
            continue

if __name__ == "__main__":
    main()
'''


class FakeSession:
    def __init__(self, live: bool = True):
        self.live = live
        self.sent: list[dict] = []

    def has_live_session(self) -> bool:
        return self.live

    async def send_json(self, obj: dict) -> bool:
        if not self.live:
            return False
        self.sent.append(obj)
        return True


def _write_fake(tmp_path: Path) -> Path:
    path = tmp_path / "fake_acp.py"
    path.write_text(FAKE_ACP)
    return path


def _cmd(script: Path) -> str:
    return f"{shlex.quote(sys.executable)} {shlex.quote(str(script))}"


def _backend(
    tmp_path: Path,
    *,
    yolo: bool = True,
    env: dict[str, str] | None = None,
    name: str = "Grok",
    timeout_sec: float = 8.0,
) -> AcpBackend:
    script = _write_fake(tmp_path)
    extra = env or {}
    for key, value in extra.items():
        os.environ[key] = value
    return AcpBackend(
        _cmd(script),
        cwd=str(tmp_path),
        display_name=name,
        yolo=yolo,
        timeout_sec=timeout_sec,
    )


def _trace_methods(path: Path) -> list[str]:
    methods = []
    if not path.exists():
        return methods
    for line in path.read_text().splitlines():
        if not line:
            continue
        msg = json.loads(line)
        method = msg.get("method")
        if method:
            methods.append(method)
    return methods


def _wait_file(path: Path, timeout: float = 4.0) -> None:
    async def _run():
        deadline = asyncio.get_event_loop().time() + timeout
        while asyncio.get_event_loop().time() < deadline:
            if path.exists():
                return
            await asyncio.sleep(0.02)
        raise TimeoutError(f"missing {path}")

    return _run()


def test_permission_yolo_selects_allow_always_by_kind():
    options = [
        {"optionId": "allow", "kind": "reject_once"},
        {"optionId": "keep-forever", "kind": "allow_always"},
        {"optionId": "once", "kind": "allow_once"},
    ]
    result = permission_result(options, yolo=True)
    assert result["outcome"]["outcome"] == "selected"
    assert result["outcome"]["optionId"] == "keep-forever"


def test_permission_yolo_falls_back_to_allow_once():
    options = [
        {"optionId": "allow", "kind": "reject_once"},
        {"optionId": "once", "kind": "allow_once"},
    ]
    result = permission_result(options, yolo=True)
    assert result["outcome"]["optionId"] == "once"


def test_permission_non_yolo_selects_reject():
    options = [
        {"optionId": "allow", "kind": "reject_once"},
        {"optionId": "keep-forever", "kind": "allow_always"},
    ]
    result = permission_result(options, yolo=False)
    assert result["outcome"]["optionId"] == "allow"


def test_permission_non_yolo_cancelled_without_reject():
    options = [
        {"optionId": "keep-forever", "kind": "allow_always"},
        {"optionId": "once", "kind": "allow_once"},
    ]
    result = permission_result(options, yolo=False)
    assert result == {"outcome": {"outcome": "cancelled"}}


def test_permission_skips_non_string_fields():
    options = [
        {"optionId": 1, "kind": "allow_always"},
        {"optionId": "ok", "kind": "allow_once"},
    ]
    result = permission_result(options, yolo=True)
    assert result["outcome"]["optionId"] == "ok"


def test_backend_kind_auto_acp_when_cmd_set(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_AGENT_ACP_CMD", "grok agent --always-approve stdio")
    monkeypatch.delenv("GLASS_AGENT_BACKEND", raising=False)
    monkeypatch.delenv("GLASS_AGENT_URL", raising=False)
    cfg = Config.from_env()
    assert cfg.backend_kind() == "acp"
    assert cfg.agent_acp_yolo is True
    assert cfg.agent_acp_name == "Grok"


def test_backend_kind_auto_grokbot_when_gateway_file_exists(monkeypatch, tmp_path):
    gw = tmp_path / "gateway.json"
    gw.write_text("{}", encoding="utf-8")
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_GROKBOT_GATEWAY_PATH", str(gw))
    monkeypatch.setenv("GLASS_AGENT_ACP_CMD", "grok agent --always-approve stdio")
    monkeypatch.delenv("GLASS_AGENT_BACKEND", raising=False)
    monkeypatch.delenv("GLASS_AGENT_URL", raising=False)
    assert Config.from_env().backend_kind() == "grokbot"


def test_backend_kind_explicit_acp_wins_over_gateway(monkeypatch, tmp_path):
    gw = tmp_path / "gateway.json"
    gw.write_text("{}", encoding="utf-8")
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_GROKBOT_GATEWAY_PATH", str(gw))
    monkeypatch.setenv("GLASS_AGENT_ACP_CMD", "grok agent stdio")
    monkeypatch.setenv("GLASS_AGENT_BACKEND", "acp")
    assert Config.from_env().backend_kind() == "acp"


def test_backend_kind_explicit_echo_wins(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_AGENT_ACP_CMD", "grok agent stdio")
    monkeypatch.setenv("GLASS_AGENT_BACKEND", "echo")
    assert Config.from_env().backend_kind() == "echo"


def test_acp_yolo_false_from_env(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_AGENT_ACP_YOLO", "0")
    assert Config.from_env().agent_acp_yolo is False


def test_select_backend_acp_with_cmd(tmp_path):
    script = _write_fake(tmp_path)
    backend = select_backend(
        kind="acp",
        url=None,
        timeout_sec=5,
        acp_cmd=_cmd(script),
        acp_name="Bot",
    )
    assert backend.name == "acp"


@pytest.mark.asyncio
async def test_acp_yolo_complete_after_permission(tmp_path, monkeypatch):
    side = tmp_path / "side"
    trace = tmp_path / "trace.jsonl"
    perm = tmp_path / "perm.jsonl"
    fs_err = tmp_path / "fs.jsonl"
    monkeypatch.setenv("FAKE_ACP_SIDE", str(side))
    monkeypatch.setenv("FAKE_ACP_TRACE", str(trace))
    monkeypatch.setenv("FAKE_ACP_PERM", str(perm))
    monkeypatch.setenv("FAKE_ACP_FS", str(fs_err))
    monkeypatch.setenv("FAKE_ACP_SEND_FS", "1")
    monkeypatch.setenv("FAKE_ACP_TEXT", "scripted")
    backend = _backend(tmp_path, yolo=True)
    try:
        agents = await backend.list_agents()
        assert agents[0].id == "acp"
        assert agents[0].name == "Grok"
        text = await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert text == "scripted"
        assert "secret-thought" not in text
        assert side.exists()
        methods = _trace_methods(trace)
        assert methods.count("initialize") == 1
        assert methods.count("session/new") == 1
        assert methods.count("session/prompt") == 1
        init = next(
            json.loads(line)
            for line in trace.read_text().splitlines()
            if json.loads(line).get("method") == "initialize"
        )
        params = init["params"]
        assert params["protocolVersion"] == 1
        assert isinstance(params["protocolVersion"], int)
        assert params["clientInfo"] == {"name": "glass-peer", "version": "0.1.0"}
        assert params["clientCapabilities"] == {}
        assert "capabilities" not in params
        perm_rows = [json.loads(line) for line in perm.read_text().splitlines()]
        assert perm_rows[0]["outcome"]["optionId"] == "keep-forever"
        await asyncio.wait_for(_wait_file(fs_err), 2)
        fs = json.loads(fs_err.read_text().splitlines()[0])
        assert fs["code"] == -32601
        assert isinstance(fs["code"], int)
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_acp_yolo_allow_once_fallback(tmp_path, monkeypatch):
    side = tmp_path / "side"
    perm = tmp_path / "perm.jsonl"
    monkeypatch.setenv("FAKE_ACP_SIDE", str(side))
    monkeypatch.setenv("FAKE_ACP_PERM", str(perm))
    monkeypatch.setenv(
        "FAKE_ACP_OPTIONS",
        json.dumps(
            [
                {"optionId": "allow", "name": "Nope", "kind": "reject_once"},
                {"optionId": "once", "name": "Once", "kind": "allow_once"},
            ]
        ),
    )
    backend = _backend(tmp_path, yolo=True)
    try:
        text = await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert text == "scripted"
        assert side.exists()
        chosen = json.loads(perm.read_text().splitlines()[0])
        assert chosen["outcome"]["optionId"] == "once"
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_acp_non_yolo_reject_no_side_effect(tmp_path, monkeypatch):
    side = tmp_path / "side"
    perm = tmp_path / "perm.jsonl"
    monkeypatch.setenv("FAKE_ACP_SIDE", str(side))
    monkeypatch.setenv("FAKE_ACP_PERM", str(perm))
    backend = _backend(tmp_path, yolo=False)
    try:
        text = await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert text == ""
        assert not side.exists()
        chosen = json.loads(perm.read_text().splitlines()[0])
        assert chosen["outcome"]["optionId"] == "allow"
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_acp_non_yolo_cancelled_no_side_effect(tmp_path, monkeypatch):
    side = tmp_path / "side"
    perm = tmp_path / "perm.jsonl"
    monkeypatch.setenv("FAKE_ACP_SIDE", str(side))
    monkeypatch.setenv("FAKE_ACP_PERM", str(perm))
    monkeypatch.setenv(
        "FAKE_ACP_OPTIONS",
        json.dumps(
            [
                {"optionId": "keep-forever", "name": "Always", "kind": "allow_always"},
                {"optionId": "once", "name": "Once", "kind": "allow_once"},
            ]
        ),
    )
    backend = _backend(tmp_path, yolo=False)
    try:
        with pytest.raises(AgentUnavailable, match="cancelled"):
            await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert not side.exists()
        chosen = json.loads(perm.read_text().splitlines()[0])
        assert chosen["outcome"]["outcome"] == "cancelled"
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_acp_kick_cancels_inflight(tmp_path, monkeypatch):
    ready = tmp_path / "ready"
    side = tmp_path / "side"
    trace = tmp_path / "trace.jsonl"
    monkeypatch.setenv("FAKE_ACP_READY", str(ready))
    monkeypatch.setenv("FAKE_ACP_SIDE", str(side))
    monkeypatch.setenv("FAKE_ACP_TRACE", str(trace))
    monkeypatch.setenv("FAKE_ACP_HANG", "1")
    backend = _backend(tmp_path, yolo=True)
    try:
        task = asyncio.create_task(
            backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        )
        await asyncio.wait_for(_wait_file(ready), 4)
        await backend.cancel()
        with pytest.raises(AgentUnavailable):
            await asyncio.wait_for(task, 4)
        assert not side.exists()
        assert "session/cancel" in _trace_methods(trace)
    finally:
        if not task.done():
            task.cancel()
        await backend.cancel()


@pytest.mark.asyncio
async def test_acp_child_death_respawns(tmp_path, monkeypatch):
    trace = tmp_path / "trace.jsonl"
    monkeypatch.setenv("FAKE_ACP_TRACE", str(trace))
    monkeypatch.setenv("FAKE_ACP_DIE_AFTER", "1")
    backend = _backend(tmp_path, yolo=True)
    try:
        first = await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert first == "scripted"
        deadline = asyncio.get_running_loop().time() + 2
        while backend._client.alive() and asyncio.get_running_loop().time() < deadline:
            await asyncio.sleep(0.02)
        second = await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert second == "scripted"
        methods = _trace_methods(trace)
        assert methods.count("initialize") == 2
        assert methods.count("session/new") == 2
        assert methods.count("session/prompt") == 2
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_acp_reuses_session_across_prompts(tmp_path, monkeypatch):
    trace = tmp_path / "trace.jsonl"
    monkeypatch.setenv("FAKE_ACP_TRACE", str(trace))
    backend = _backend(tmp_path, yolo=True)
    try:
        await backend.complete(text="one", agent_id=None, phone_peer=PHONE)
        await backend.complete(text="two", agent_id=None, phone_peer=PHONE)
        methods = _trace_methods(trace)
        assert methods.count("initialize") == 1
        assert methods.count("session/new") == 1
        assert methods.count("session/prompt") == 2
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_cancelled_complete_does_not_persist_inbox(tmp_path, monkeypatch):
    side = tmp_path / "side"
    monkeypatch.setenv("FAKE_ACP_SIDE", str(side))
    monkeypatch.setenv(
        "FAKE_ACP_OPTIONS",
        json.dumps(
            [{"optionId": "keep-forever", "name": "Always", "kind": "allow_always"}]
        ),
    )
    backend = _backend(tmp_path, yolo=False)
    state = StateStore(str(tmp_path / "state"))
    session = FakeSession(live=True)
    proto = ProtocolHandler(state, backend)
    proto.bind_session(session)
    try:
        ack = await proto.handle(
            {
                "v": 1,
                "op": "send",
                "id": str(uuid.uuid4()),
                "from": "me",
                "text": "hi",
            },
            PHONE,
        )
        assert ack["ok"] is True
        await proto.wait_idle()
        assert session.sent == []
        assert state.get_reply_seq() == 0
        state_file = tmp_path / "state" / "state.json"
        if state_file.exists():
            dumped = json.loads(state_file.read_text())
            assert "messages" not in dumped
        assert not side.exists()
        replies = await proto.handle({"v": 1, "op": "replies", "id": "x"}, PHONE)
        assert replies["error"] == "replies_removed"
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_empty_complete_without_live_queues_reply(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_ACP_EMPTY", "1")
    backend = _backend(tmp_path, yolo=True)
    state = StateStore(str(tmp_path / "state"))
    session = FakeSession(live=False)
    proto = ProtocolHandler(state, backend)
    proto.bind_session(session)
    try:
        ack = await proto.handle(
            {
                "v": 1,
                "op": "send",
                "id": str(uuid.uuid4()),
                "from": "me",
                "text": "hi",
            },
            PHONE,
        )
        assert ack["ok"] is True
        await proto.wait_idle()
        assert session.sent == []
        assert state.get_reply_seq() == 1
        rows = proto._log.after(-1)
        assert len(rows) == 1
        assert rows[0]["text"] == "(no text)"
        state_file = tmp_path / "state" / "state.json"
        if state_file.exists():
            dumped = json.loads(state_file.read_text())
            assert "messages" not in dumped
    finally:
        await backend.cancel()


def test_parse_error_only_invalid_params_code():
    client = AcpClient("true")
    invalid = client._error_from_rpc({"code": -32602, "message": "bad shape"})
    assert invalid.parse_error is True
    tool = client._error_from_rpc(
        {"code": -32000, "message": "failed to parse the file"}
    )
    assert tool.parse_error is False
    untyped = client._error_from_rpc({"code": "-32602", "message": "parse"})
    assert untyped.parse_error is False


@pytest.mark.asyncio
async def test_bad_rpc_id_does_not_kill_reader(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_ACP_BAD_ID", "1")
    backend = _backend(tmp_path, yolo=True)
    try:
        text = await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert text == "scripted"
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_prompt_retry_timeout_kills_child(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_ACP_PARSE_ONCE", "1")
    monkeypatch.setenv("FAKE_ACP_HANG_PROMPT", "1")
    backend = _backend(tmp_path, yolo=True, timeout_sec=0.4)
    try:
        with pytest.raises(AgentUnavailable, match="timed out"):
            await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert not backend._client.alive()
    finally:
        await backend.cancel()


@pytest.mark.asyncio
async def test_handshake_failure_kills_child(tmp_path):
    backend = AcpBackend(
        f"{shlex.quote(sys.executable)} -c {shlex.quote('import time; time.sleep(0)')}",
        cwd=str(tmp_path),
        timeout_sec=5,
    )
    try:
        with pytest.raises(AgentUnavailable):
            await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert not backend._client.alive()
    finally:
        await backend.cancel()
