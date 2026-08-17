"""GrokBotBackend listAgents + sendPrompt/tail wait (fake gateway)."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
from pathlib import Path

import pytest
from aiohttp import web

from agent import AgentUnavailable, select_backend
from config import Config
from grokbot import (
    GrokBotBackend,
    decode_blob_stem,
    read_last_roster,
)
from session import bound_port

PHONE = "a" * 52

ADA_ID = "aaaaaaaa-1111-4000-8000-000000000001"
BEA_ID = "bbbbbbbb-2222-4000-8000-000000000002"
CAI_ID = "cccccccc-3333-4000-8000-000000000003"

ROSTER_KEY = "sand.client.slice.account.fixture.roster.last-roster"
LAST_AGENT_KEY = "sand.client.slice.account.fixture.selection.last-agent"

LIVE_ROWS = [
    {
        "id": ADA_ID,
        "name": "Ada",
        "isGroup": False,
        "isHiddenFromSidebar": False,
        "avatarDataUrl": "data:image/png;base64,AAA",
    },
    {
        "id": "group-1",
        "name": "Team",
        "isGroup": True,
        "isHiddenFromSidebar": False,
    },
    {
        "id": "hidden-1",
        "name": "Hidden",
        "isGroup": False,
        "isHiddenFromSidebar": True,
    },
    {
        "id": BEA_ID,
        "name": "Bea",
        "isGroup": False,
        "isHiddenFromSidebar": False,
    },
    {"id": "", "name": "NoId"},
    {"name": "MissingId"},
    {"agentId": CAI_ID, "title": "Cai"},
]

DISK_ROWS = [
    {
        "id": ADA_ID,
        "name": "Ada",
        "isGroup": False,
        "isHiddenFromSidebar": False,
    },
    {"id": "group-disk", "name": "Disk Group", "isGroup": True},
    {
        "id": BEA_ID,
        "name": "Bea",
        "isGroup": False,
        "isHiddenFromSidebar": False,
    },
]

FILTERED_LIVE = [
    (ADA_ID, "Ada"),
    (BEA_ID, "Bea"),
    (CAI_ID, "Cai"),
]


def encode_blob_stem(key: str) -> str:
    return base64.b32encode(key.encode("utf-8")).decode("ascii").rstrip("=").lower()


def write_blob(root: Path, key: str, value: dict, schema: int = 2) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    path = root / f"{encode_blob_stem(key)}.blob"
    path.write_text(
        json.dumps({"schemaVersion": schema, "value": value}),
        encoding="utf-8",
    )
    return path


def write_gateway(path: Path, port: int, token: str = "test-token") -> None:
    path.write_text(
        json.dumps({"baseUrl": f"http://127.0.0.1:{port}", "token": token}),
        encoding="utf-8",
    )


def write_disk_roster(root: Path) -> None:
    write_blob(root, ROSTER_KEY, {"rows": DISK_ROWS}, schema=2)
    write_blob(root, LAST_AGENT_KEY, {"agentId": BEA_ID}, schema=1)


async def start_list_server(handler) -> tuple[web.AppRunner, int]:
    app = web.Application()
    app.router.add_post("/api/listAgents", handler)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    return runner, bound_port(runner)


def make_backend(tmp_path: Path, port: int | None, **kwargs) -> GrokBotBackend:
    gw = tmp_path / "gateway.json"
    persist = tmp_path / "persist"
    if port is not None:
        write_gateway(gw, port)
    persist.mkdir(parents=True, exist_ok=True)
    return GrokBotBackend(
        gateway_path=str(gw),
        persistence_dir=str(persist),
        timeout_sec=kwargs.get("timeout_sec", 5),
        poll_sec=kwargs.get("poll_sec", 0.05),
        tail_limit=kwargs.get("tail_limit", 50),
        tail_pages=kwargs.get("tail_pages", 4),
    )


def _user_echo(nonce: str) -> dict:
    return {"kind": "message", "role": "user", "clientNonce": nonce, "content": "hi"}


def _user_other(nonce: str = "other-turn") -> dict:
    return {"kind": "message", "role": "user", "clientNonce": nonce, "content": "next"}


def _sm_text(content: str, **extra) -> dict:
    row = {"kind": "send-message", "message": {"type": "text", "content": content}}
    row.update(extra)
    return row


def live_shaped_entries(nonce: str) -> list[dict]:
    return [
        {
            "kind": "message",
            "role": "user",
            "clientNonce": nonce,
            "content": "hi",
        },
        {
            "kind": "message",
            "role": "assistant",
            "content": "chip MUST NOT JOIN",
            "toAgent": {"id": BEA_ID, "name": "Bea", "kind": "agent"},
        },
        {
            "kind": "send-message",
            "content": "TOP LEVEL MUST NOT JOIN",
            "message": {"type": "widget", "content": "widget MUST NOT JOIN"},
        },
        {
            "kind": "send-message",
            "content": "TOP LEVEL MUST NOT JOIN",
            "message": {"type": "text", "content": "Hello from Ada"},
        },
        {"kind": "event", "type": "tool"},
        {
            "kind": "send-message",
            "message": {"type": "text", "content": "  Second bubble  "},
        },
        {
            "kind": "message",
            "role": "user",
            "clientNonce": "other-turn",
            "content": "next",
        },
        {
            "kind": "send-message",
            "message": {"type": "text", "content": "Later turn must not appear"},
        },
    ]


class FakeGrokGateway:
    def __init__(self, mode: str = "live"):
        self.mode = mode
        self.sends: list[dict] = []
        self.tails: list[dict] = []
        self.list_calls = 0
        self.list_status = 200
        self.list_payload: object = LIVE_ROWS
        self.list_fail_after: int | None = None
        self.send_response: object = {"accepted": True}
        self.send_status = 200
        self.tail_polls = 0
        self.hang = asyncio.Event()
        self.hung = asyncio.Event()
        self.cut_ready_after = 3

    async def handle_list(self, request):
        self.list_calls += 1
        if self.list_fail_after is not None and self.list_calls > self.list_fail_after:
            return web.json_response({}, status=503)
        if self.list_status != 200:
            return web.json_response({}, status=self.list_status)
        return web.json_response(self.list_payload)

    async def handle_send(self, request):
        body = await request.json()
        self.sends.append(body)
        if self.send_status != 200:
            return web.json_response({}, status=self.send_status)
        if isinstance(self.send_response, dict) or self.send_response is None:
            return web.json_response(self.send_response or {})
        return web.json_response(self.send_response)

    async def handle_tail(self, request):
        body = await request.json()
        assert "agentId" not in body
        assert isinstance(body.get("id"), str) and body["id"]
        self.tails.append(body)
        self.tail_polls += 1
        if self.mode == "hang" or (
            self.mode == "first_then_hang" and self.tail_polls > 1
        ):
            self.hung.set()
            await self.hang.wait()
        nonce = self.sends[-1]["clientNonce"] if self.sends else ""
        return web.json_response(self._tail_payload(nonce, body))

    def _tail_payload(self, nonce: str, body: dict) -> dict:
        if self.mode == "live":
            return {"entries": live_shaped_entries(nonce)}
        if self.mode == "assistant_only":
            return {
                "entries": [
                    {"kind": "message", "role": "user", "clientNonce": nonce},
                    {
                        "kind": "message",
                        "role": "assistant",
                        "content": "NOT A BUBBLE",
                        "toAgent": {
                            "id": BEA_ID,
                            "name": "Bea",
                            "kind": "agent",
                        },
                    },
                    {
                        "kind": "message",
                        "role": "assistant",
                        "content": "ALSO NOT A BUBBLE",
                    },
                ]
            }
        if self.mode == "tool_only":
            return {
                "entries": [
                    {"kind": "message", "role": "user", "clientNonce": nonce},
                    {"kind": "event", "type": "tool"},
                    {
                        "kind": "send-message",
                        "message": {"type": "widget", "content": "nope"},
                    },
                ]
            }
        if self.mode == "page2":
            if "beforeSeq" not in body:
                return {
                    "entries": [{"kind": "event", "type": "noise"}],
                    "nextBeforeSeq": 10,
                }
            return {
                "entries": [
                    {"kind": "message", "role": "user", "id": nonce},
                    {
                        "kind": "send-message",
                        "message": {"type": "text", "content": "from page 2"},
                    },
                    _user_other(),
                ],
                "nextBeforeSeq": None,
            }
        if self.mode == "missing_echo":
            return {
                "entries": [{"kind": "event", "n": self.tail_polls}],
                "nextBeforeSeq": 1000 - self.tail_polls,
            }
        if self.mode == "cut_then_text":
            echo = {"kind": "message", "role": "user", "clientNonce": nonce}
            other = {
                "kind": "message",
                "role": "user",
                "clientNonce": "later",
            }
            if self.tail_polls < self.cut_ready_after:
                return {"entries": [echo, other]}
            return {
                "entries": [
                    echo,
                    {
                        "kind": "send-message",
                        "message": {"type": "text", "content": "finally"},
                    },
                    other,
                ]
            }
        if self.mode == "staged":
            echo = _user_echo(nonce)
            first = _sm_text("I'll check.")
            event = {"kind": "event", "type": "tool"}
            second = _sm_text("Here's the report.")
            if self.tail_polls <= 2:
                return {"entries": [echo, first]}
            if self.tail_polls <= 4:
                return {"entries": [echo, first, event, second]}
            return {"entries": [echo, first, event, second, _user_other()]}
        if self.mode == "staged_no_cut":
            echo = _user_echo(nonce)
            first = _sm_text("I'll check.")
            second = _sm_text("Here's the report.")
            if self.tail_polls <= 2:
                return {"entries": [echo, first]}
            return {"entries": [echo, first, {"kind": "event", "type": "tool"}, second]}
        if self.mode == "quiet_late":
            echo = _user_echo(nonce)
            first = _sm_text("I'll check.")
            second = _sm_text("Here's the report.")
            if self.tail_polls <= 8:
                return {"entries": [echo, first]}
            if self.tail_polls == 9:
                return {"entries": [echo, first, second]}
            return {"entries": [echo, first, second, _user_other()]}
        if self.mode == "cut_streaming":
            echo = _user_echo(nonce)
            first = _sm_text("I'll check.")
            second = _sm_text("Here's the report.", streaming=True)
            if self.tail_polls == 1:
                return {"entries": [echo, first]}
            return {"entries": [echo, first, second, _user_other()]}
        if self.mode == "cut_after_first":
            return {
                "entries": [
                    _user_echo(nonce),
                    _sm_text("I'll check."),
                    _user_other(),
                ]
            }
        if self.mode == "first_then_hang":
            return {
                "entries": [
                    _user_echo(nonce),
                    _sm_text("I'll check."),
                ]
            }
        return {"entries": live_shaped_entries(nonce)}


async def start_fake_gateway(gw: FakeGrokGateway) -> tuple[web.AppRunner, int]:
    app = web.Application()
    app.router.add_post("/api/listAgents", gw.handle_list)
    app.router.add_post("/api/sendPrompt", gw.handle_send)
    app.router.add_post("/api/getAgentTranscriptTail", gw.handle_tail)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    return runner, bound_port(runner)


def test_decode_blob_stem_rejects_filename_with_suffix():
    stem = encode_blob_stem(ROSTER_KEY)
    assert decode_blob_stem(stem) == ROSTER_KEY
    assert decode_blob_stem(f"{stem}.blob") is None


def test_read_last_roster_uses_stem_not_filename(tmp_path):
    persist = tmp_path / "persist"
    write_disk_roster(persist)
    agents = read_last_roster(str(persist))
    assert [(a.id, a.name) for a in agents] == [
        (ADA_ID, "Ada"),
        (BEA_ID, "Bea"),
    ]


@pytest.mark.asyncio
async def test_list_roster_live_filters_and_last_agent(tmp_path):
    async def list_agents(request):
        body = await request.json()
        assert body == {}
        assert request.headers.get("Authorization") == "Bearer test-token"
        return web.json_response(LIVE_ROWS)

    runner, port = await start_list_server(list_agents)
    try:
        backend = make_backend(tmp_path, port)
        write_disk_roster(tmp_path / "persist")
        roster = await backend.list_roster()
        assert [(a.id, a.name) for a in roster.agents] == FILTERED_LIVE
        for agent in roster.agents:
            assert set(agent.__dataclass_fields__) == {"id", "name"}
        assert roster.stale is False
        assert roster.source == "live"
        assert roster.last_agent_id == BEA_ID
        cached = await backend.name_for(ADA_ID)
        assert cached is not None
        assert cached.name == "Ada"
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_list_roster_503_uses_disk_stale(tmp_path):
    async def list_agents(request):
        return web.json_response({"error": "unavailable"}, status=503)

    runner, port = await start_list_server(list_agents)
    try:
        backend = make_backend(tmp_path, port)
        write_disk_roster(tmp_path / "persist")
        roster = await backend.list_roster()
        assert roster.stale is True
        assert roster.source == "last-roster"
        assert [(a.id, a.name) for a in roster.agents] == [
            (ADA_ID, "Ada"),
            (BEA_ID, "Bea"),
        ]
        assert roster.last_agent_id == BEA_ID
        assert await backend.name_for(ADA_ID) is None
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_list_roster_connection_reset_uses_disk_stale(tmp_path):
    async def list_agents(request):
        transport = request.transport
        if transport is not None:
            transport.abort()
        await asyncio.sleep(0)
        return web.Response()

    runner, port = await start_list_server(list_agents)
    try:
        backend = make_backend(tmp_path, port)
        write_disk_roster(tmp_path / "persist")
        roster = await backend.list_roster()
        assert roster.stale is True
        assert roster.source == "last-roster"
        assert [a.id for a in roster.agents] == [ADA_ID, BEA_ID]
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_list_roster_no_gateway_no_blobs_raises(tmp_path):
    backend = GrokBotBackend(
        gateway_path=str(tmp_path / "missing-gateway.json"),
        persistence_dir=str(tmp_path / "missing-persist"),
        timeout_sec=5,
    )
    with pytest.raises(AgentUnavailable) as exc:
        await backend.list_roster()
    assert exc.value.detail == "no_gateway"


@pytest.mark.asyncio
async def test_list_roster_live_empty_not_disk(tmp_path):
    async def list_agents(request):
        return web.json_response([])

    runner, port = await start_list_server(list_agents)
    try:
        backend = make_backend(tmp_path, port)
        write_disk_roster(tmp_path / "persist")
        roster = await backend.list_roster()
        assert roster.agents == []
        assert roster.stale is False
        assert roster.source == "live"
        assert roster.last_agent_id is None
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_collects_this_turn_sm_text_only(tmp_path):
    gw = FakeGrokGateway(mode="live")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port)
        text = await backend.complete(
            text="ping from glass", agent_id=ADA_ID, phone_peer=PHONE
        )
        assert text == "Hello from Ada\n\nSecond bubble"
        assert "chip" not in text
        assert "TOP LEVEL" not in text
        assert "widget" not in text
        assert "Later turn" not in text
        assert len(gw.sends) == 1
        send = gw.sends[0]
        assert send["agentId"] == ADA_ID
        assert send["prompt"] == "ping from glass"
        assert send["clientNonce"]
        assert "directAddressedAcceptance" not in send
        assert set(send) == {"agentId", "prompt", "clientNonce"}
        assert gw.tails
        assert gw.tails[0]["id"] == ADA_ID
        assert gw.tails[0]["limit"] == 50
        assert "agentId" not in gw.tails[0]
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_assistant_only_is_not_a_bubble(tmp_path):
    gw = FakeGrokGateway(mode="assistant_only")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=0.35, poll_sec=0.05)
        text = await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert text == ""
        assert gw.sends
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_page2_nonce_echo_still_collects(tmp_path):
    gw = FakeGrokGateway(mode="page2")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(
            tmp_path, port, timeout_sec=2, poll_sec=0.05, tail_pages=4
        )
        text = await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert text == "from page 2"
        assert any("beforeSeq" in body for body in gw.tails)
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_missing_echo_times_out(tmp_path):
    gw = FakeGrokGateway(mode="missing_echo")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(
            tmp_path, port, timeout_sec=0.35, poll_sec=0.08, tail_pages=2
        )
        with pytest.raises(AgentUnavailable) as exc:
            await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert exc.value.detail == "timeout"
        # Must keep polling after the first page walk, not stop on page 1.
        assert len(gw.tails) > 1 + 2
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_other_user_cut_before_sm_text_keeps_polling(tmp_path):
    gw = FakeGrokGateway(mode="cut_then_text")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        text = await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert text == "finally"
        assert gw.tail_polls >= 3
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_unknown_agent_does_not_send(tmp_path):
    gw = FakeGrokGateway()
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port)
        with pytest.raises(AgentUnavailable) as exc:
            await backend.complete(
                text="hi", agent_id="not-a-real-id", phone_peer=PHONE
            )
        assert exc.value.detail == "unknown_agent"
        assert gw.sends == []
        assert gw.tails == []
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_live_list_503_is_no_gateway(tmp_path):
    gw = FakeGrokGateway()
    gw.list_status = 503
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port)
        write_disk_roster(tmp_path / "persist")
        with pytest.raises(AgentUnavailable) as exc:
            await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert exc.value.detail == "no_gateway"
        assert gw.sends == []
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_tool_only_timeout_returns_empty(tmp_path):
    gw = FakeGrokGateway(mode="tool_only")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=0.35, poll_sec=0.05)
        text = await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert text == ""
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_cancel_during_tail(tmp_path):
    gw = FakeGrokGateway(mode="hang")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=5)
        task = asyncio.create_task(
            backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        )
        await asyncio.wait_for(gw.hung.wait(), 2)
        await backend.cancel()
        with pytest.raises(AgentUnavailable) as exc:
            await asyncio.wait_for(task, 2)
        assert exc.value.detail == "cancelled"
    finally:
        gw.hang.set()
        await runner.cleanup()


@pytest.mark.asyncio
async def test_task_cancel_logs_watch_end_cancel(tmp_path, caplog):
    gw = FakeGrokGateway(mode="hang")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=5)
        caplog.set_level(logging.INFO)
        task = asyncio.create_task(
            backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        )
        await asyncio.wait_for(gw.hung.wait(), 2)
        task.cancel()
        with pytest.raises(AgentUnavailable) as exc:
            await asyncio.wait_for(task, 2)
        assert exc.value.detail == "cancelled"
        assert any(
            "grokbot watch end reason=cancel" in rec.getMessage()
            for rec in caplog.records
        )
    finally:
        gw.hang.set()
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_rejected_send(tmp_path):
    gw = FakeGrokGateway()
    gw.send_response = {"accepted": False}
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port)
        with pytest.raises(AgentUnavailable) as exc:
            await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert exc.value.detail == "rejected"
        assert gw.tails == []
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_missing_accepted_still_collects(tmp_path):
    gw = FakeGrokGateway()
    gw.send_response = {}
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port)
        text = await backend.complete(text="hi", agent_id=ADA_ID, phone_peer=PHONE)
        assert text == "Hello from Ada\n\nSecond bubble"
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_blank_agent_uses_last_agent(tmp_path):
    gw = FakeGrokGateway()
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port)
        write_disk_roster(tmp_path / "persist")
        text = await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert text == "Hello from Ada\n\nSecond bubble"
        assert gw.sends[0]["agentId"] == BEA_ID
        named = await backend.name_for(None)
        assert named is not None
        assert named.id == BEA_ID
        assert named.name == "Bea"
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_complete_empty_live_list_is_no_agent(tmp_path):
    gw = FakeGrokGateway()
    gw.list_payload = []
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port)
        with pytest.raises(AgentUnavailable) as exc:
            await backend.complete(text="hi", agent_id=None, phone_peer=PHONE)
        assert exc.value.detail == "no_agent"
        assert gw.sends == []
    finally:
        await runner.cleanup()


def test_select_backend_grokbot(tmp_path):
    backend = select_backend(
        kind="grokbot",
        url=None,
        timeout_sec=1,
        grokbot_gateway_path=str(tmp_path / "gateway.json"),
        grokbot_persistence_dir=str(tmp_path / "persist"),
    )
    assert backend.name == "grokbot"


def test_backend_kind_explicit_grokbot(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_AGENT_BACKEND", "grokbot")
    monkeypatch.setenv("GLASS_AGENT_ACP_CMD", "grok agent stdio")
    cfg = Config.from_env()
    assert cfg.backend_kind() == "grokbot"
    assert cfg.grokbot_gateway_path == str(tmp_path / "no-such-gateway.json")
    assert cfg.grokbot_persistence_dir == str(tmp_path / "no-such-persist")


def test_newest_last_roster_mtime_wins(tmp_path):
    persist = tmp_path / "persist"
    older_key = "sand.client.slice.account.old.roster.last-roster"
    newer_key = "sand.client.slice.account.new.roster.last-roster"
    old_path = write_blob(
        persist,
        older_key,
        {"rows": [{"id": "old", "name": "Old"}]},
    )
    new_path = write_blob(
        persist,
        newer_key,
        {"rows": [{"id": ADA_ID, "name": "Ada"}]},
    )
    os.utime(old_path, (1_000_000, 1_000_000))
    os.utime(new_path, (2_000_000, 2_000_000))
    agents = read_last_roster(str(persist))
    assert [a.id for a in agents] == [ADA_ID]


def test_schema1_agents_dict(tmp_path):
    persist = tmp_path / "persist"
    write_blob(
        persist,
        ROSTER_KEY,
        {"agents": {ADA_ID: {"name": "Ada"}, BEA_ID: {"name": "Bea"}}},
        schema=1,
    )
    agents = read_last_roster(str(persist))
    assert {(a.id, a.name) for a in agents} == {
        (ADA_ID, "Ada"),
        (BEA_ID, "Bea"),
    }


async def _collect_bubbles(backend: GrokBotBackend, text: str = "hi") -> list[str]:
    out: list[str] = []
    async for bubble in backend.stream_complete(
        text=text, agent_id=ADA_ID, phone_peer=PHONE
    ):
        out.append(bubble)
    return out


@pytest.mark.asyncio
async def test_stream_complete_staged_yields_two_then_join(tmp_path):
    gw = FakeGrokGateway(mode="staged")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        bubbles = await _collect_bubbles(backend)
        assert bubbles == ["I'll check.", "Here's the report."]
        joined = await backend.complete(
            text="again", agent_id=ADA_ID, phone_peer=PHONE
        )
        assert joined == "I'll check.\n\nHere's the report."
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_stream_complete_quiet_then_late_bubble(tmp_path):
    gw = FakeGrokGateway(mode="quiet_late")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        bubbles = await _collect_bubbles(backend)
        assert bubbles == ["I'll check.", "Here's the report."]
        assert gw.tail_polls >= 9
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_stream_complete_cut_streaming_yields_inflight(tmp_path):
    gw = FakeGrokGateway(mode="cut_streaming")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        bubbles = await _collect_bubbles(backend)
        assert bubbles == ["I'll check.", "Here's the report."]
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_stream_complete_cut_after_first_stops(tmp_path):
    gw = FakeGrokGateway(mode="cut_after_first")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        bubbles = await _collect_bubbles(backend)
        assert bubbles == ["I'll check."]
        assert gw.tail_polls == 1
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_cancel_then_second_stream_complete_still_yields(tmp_path):
    gw = FakeGrokGateway(mode="hang")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=5, poll_sec=0.05)
        task = asyncio.create_task(_collect_bubbles(backend))
        await asyncio.wait_for(gw.hung.wait(), 2)
        await backend.cancel()
        with pytest.raises(AgentUnavailable) as exc:
            await asyncio.wait_for(task, 2)
        assert exc.value.detail == "cancelled"
        gw.hang.set()
        gw.mode = "live"
        bubbles = await _collect_bubbles(backend)
        assert bubbles == ["Hello from Ada", "Second bubble"]
    finally:
        gw.hang.set()
        await runner.cleanup()


@pytest.mark.asyncio
async def test_stream_complete_timeout_after_bubbles_no_empty(tmp_path):
    gw = FakeGrokGateway(mode="staged_no_cut")
    runner, port = await start_fake_gateway(gw)
    try:
        backend = make_backend(tmp_path, port, timeout_sec=0.35, poll_sec=0.05)
        bubbles = await _collect_bubbles(backend)
        assert bubbles == ["I'll check.", "Here's the report."]
    finally:
        await runner.cleanup()
