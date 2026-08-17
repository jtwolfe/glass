"""Tests for session protocol handler."""

import asyncio
import json
import uuid

import pytest

from agent import AgentInfo, AgentUnavailable, EchoBackend
from grokbot import GrokBotRoster
from protocol import ProtocolHandler
from state import StateStore
from tests.test_grokbot import (
    ADA_ID,
    FakeGrokGateway,
    make_backend,
    start_fake_gateway,
)


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


@pytest.fixture
def handler(tmp_path):
    state = StateStore(str(tmp_path))
    proto = ProtocolHandler(state, EchoBackend())
    proto.bind_session(FakeSession())
    return proto


@pytest.mark.asyncio
async def test_handle_send_ack_has_request_id_and_echo_id(handler):
    req_id = str(uuid.uuid4())
    msg = {
        "v": 1,
        "op": "send",
        "id": req_id,
        "from": "me",
        "text": "Hello world",
        "at": "2024-01-01T00:00:00Z",
    }

    result = await handler.handle(msg, "a" * 52)

    assert result["ok"] is True
    assert result["v"] == 1
    assert result["id"] == req_id
    assert result["echoId"]
    assert result["echoId"] != req_id
    assert result["from"] == "me"
    assert result["text"] == "Hello world"
    assert "op" not in result

    await handler.wait_idle()
    replies = [f for f in handler._session.sent if f.get("op") == "reply"]
    assert len(replies) == 1
    assert replies[0]["text"] == "echo: Hello world"
    assert replies[0]["from"] == "Echo"
    assert replies[0]["agentId"] == "echo"
    assert replies[0]["seq"] == 0
    assert replies[0]["inReplyTo"] == result["echoId"]
    assert replies[0]["id"] != req_id
    assert not any(f.get("op") == "error" for f in handler._session.sent)


@pytest.mark.asyncio
async def test_handle_agents_from_echo(handler):
    msg = {"v": 1, "op": "agents", "id": "agents-1"}

    result = await handler.handle(msg, "a" * 52)

    assert result["ok"] is True
    assert result["id"] == "agents-1"
    assert result["agents"] == [{"id": "echo", "name": "Echo"}]
    assert "stale" not in result
    assert "lastAgentId" not in result


class RosterBackend:
    name = "grokbot"

    def __init__(
        self,
        agents: list[AgentInfo] | None = None,
        *,
        stale: bool = False,
        last_agent_id: str | None = "bea",
        fail: bool = False,
    ):
        self._agents = agents if agents is not None else [AgentInfo(id="a", name="A")]
        self._stale = stale
        self._last_agent_id = last_agent_id
        self._fail = fail

    async def list_agents(self) -> list[AgentInfo]:
        roster = await self.list_roster()
        return list(roster.agents)

    async def list_roster(self) -> GrokBotRoster:
        if self._fail:
            raise AgentUnavailable(detail="no_gateway")
        return GrokBotRoster(
            agents=list(self._agents),
            stale=self._stale,
            source="last-roster" if self._stale else "live",
            last_agent_id=self._last_agent_id,
        )

    async def complete(
        self, *, text: str, agent_id: str | None, phone_peer: str
    ) -> str:
        raise AgentUnavailable(detail="not_implemented")

    async def cancel(self) -> None:
        return None


@pytest.mark.asyncio
async def test_handle_agents_includes_stale_and_last_agent(tmp_path):
    state = StateStore(str(tmp_path))
    proto = ProtocolHandler(
        state,
        RosterBackend(
            [AgentInfo(id="bea", name="Bea")],
            stale=False,
            last_agent_id="bea",
        ),
    )
    proto.bind_session(FakeSession())

    result = await proto.handle({"v": 1, "op": "agents", "id": "agents-2"}, "a" * 52)

    assert result["ok"] is True
    assert result["agents"] == [{"id": "bea", "name": "Bea"}]
    assert result["stale"] is False
    assert result["lastAgentId"] == "bea"


@pytest.mark.asyncio
async def test_handle_agents_empty_live_list_is_success(tmp_path):
    state = StateStore(str(tmp_path))
    proto = ProtocolHandler(state, RosterBackend([], stale=False, last_agent_id=None))
    proto.bind_session(FakeSession())

    result = await proto.handle({"v": 1, "op": "agents", "id": "agents-3"}, "a" * 52)

    assert result == {
        "v": 1,
        "ok": True,
        "id": "agents-3",
        "agents": [],
        "stale": False,
        "lastAgentId": None,
    }


@pytest.mark.asyncio
async def test_handle_agents_roster_failure(tmp_path):
    state = StateStore(str(tmp_path))
    proto = ProtocolHandler(state, RosterBackend(fail=True))
    proto.bind_session(FakeSession())

    result = await proto.handle({"v": 1, "op": "agents", "id": "agents-4"}, "a" * 52)

    assert result["ok"] is False
    assert result["error"] == "agent_unavailable"


@pytest.mark.asyncio
async def test_handle_replies_removed(handler):
    msg = {"v": 1, "op": "replies", "after": "", "limit": 10, "id": "r1"}

    result = await handler.handle(msg, "a" * 52)

    assert result["ok"] is False
    assert result["error"] == "replies_removed"
    assert "messages" not in result


@pytest.mark.asyncio
async def test_handle_unknown_op(handler):
    result = await handler.handle({"op": "unknown"}, "a" * 52)
    assert result["ok"] is False
    assert result["error"] == "unknown_op"


@pytest.mark.asyncio
async def test_handle_invalid_json(handler):
    result = await handler.handle_raw("not json", "a" * 52)
    assert result["ok"] is False
    assert result["error"] == "invalid_json"


@pytest.mark.asyncio
async def test_handle_ping(handler):
    result = await handler.handle({"v": 1, "op": "ping", "id": "p1"}, "a" * 52)
    assert result["ok"] is True
    assert result["op"] == "pong"
    assert result["id"] == "p1"


@pytest.mark.asyncio
async def test_hello_does_not_dump_inbox(tmp_path):
    """There is no replies inbox; a later hello must not see stored sends."""
    state = StateStore(str(tmp_path))
    session = FakeSession(live=False)
    proto = ProtocolHandler(state, EchoBackend())
    proto.bind_session(session)

    await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "secret",
        },
        "a" * 52,
    )
    await proto.wait_idle()
    assert session.sent == []

    dumped = await proto.handle({"v": 1, "op": "replies", "id": "x"}, "a" * 52)
    assert dumped["ok"] is False
    assert dumped["error"] == "replies_removed"


@pytest.mark.asyncio
async def test_complete_without_live_queues_reply(tmp_path):
    state = StateStore(str(tmp_path))
    session = FakeSession(live=False)
    proto = ProtocolHandler(state, EchoBackend())
    proto.bind_session(session)

    ack = await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "gone",
        },
        "a" * 52,
    )
    assert ack["ok"] is True
    await proto.wait_idle()
    assert session.sent == []
    assert state.get_reply_seq() == 1
    rows = proto._log.after(-1)
    assert len(rows) == 1
    assert rows[0]["seq"] == 0
    assert rows[0]["text"] == "echo: gone"
    assert rows[0]["inReplyTo"] == ack["echoId"]


@pytest.mark.asyncio
async def test_unsupported_version(handler):
    result = await handler.handle(
        {"v": 2, "op": "send", "id": "x", "from": "me", "text": "hi"},
        "a" * 52,
    )
    assert result["error"] == "unsupported_version"


class FailBackend:
    name = "fail"

    def __init__(self, detail: str):
        self._detail = detail

    async def list_agents(self) -> list[AgentInfo]:
        return [AgentInfo(id="echo", name="Echo")]

    async def complete(self, *, text, agent_id, phone_peer) -> str:
        raise AgentUnavailable(detail=self._detail)

    async def cancel(self) -> None:
        return None


class HangBackend:
    name = "hang"

    def __init__(self):
        self.entered = 0
        self.first_in = asyncio.Event()
        self.cancelled = 0

    async def list_agents(self) -> list[AgentInfo]:
        return [AgentInfo(id="echo", name="Echo")]

    async def complete(self, *, text, agent_id, phone_peer) -> str:
        self.entered += 1
        if self.entered == 1:
            self.first_in.set()
        await asyncio.sleep(3600)
        return "late"

    async def cancel(self) -> None:
        self.cancelled += 1


class EmptyBackend(EchoBackend):
    async def complete(self, *, text, agent_id, phone_peer) -> str:
        return ""


HARD_DETAILS = (
    "unknown_agent",
    "no_agent",
    "no_gateway",
    "rejected",
    "timeout",
    "not_implemented",
    "gateway_error",
)


@pytest.mark.asyncio
@pytest.mark.parametrize("detail", HARD_DETAILS)
async def test_hard_failure_emits_op_error(tmp_path, detail):
    state = StateStore(str(tmp_path))
    session = FakeSession()
    proto = ProtocolHandler(state, FailBackend(detail))
    proto.bind_session(session)

    ack = await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "hi",
            "agentId": "wanted",
        },
        "a" * 52,
    )
    assert ack["ok"] is True
    await proto.wait_idle()
    errors = [f for f in session.sent if f.get("op") == "error"]
    replies = [f for f in session.sent if f.get("op") == "reply"]
    assert replies == []
    assert len(errors) == 1
    err = errors[0]
    assert err["v"] == 1
    assert err["error"] == "agent_unavailable"
    assert err["detail"] == detail
    assert err["inReplyTo"] == ack["echoId"]
    assert err["agentId"] == "wanted"
    assert "seq" not in err
    assert state.get_reply_seq() == 0


@pytest.mark.asyncio
async def test_hard_error_without_live_is_dropped(tmp_path):
    state = StateStore(str(tmp_path))
    session = FakeSession(live=False)
    proto = ProtocolHandler(state, FailBackend("timeout"))
    proto.bind_session(session)
    ack = await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "hi",
        },
        "a" * 52,
    )
    assert ack["ok"] is True
    await proto.wait_idle()
    assert session.sent == []
    assert state.get_reply_seq() == 0


@pytest.mark.asyncio
async def test_hard_error_is_not_persisted_in_session_log(tmp_path):
    state = StateStore(str(tmp_path))
    session = FakeSession()
    proto = ProtocolHandler(state, FailBackend("timeout"))
    proto.bind_session(session)
    await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "hi",
        },
        "a" * 52,
    )
    await proto.wait_idle()
    assert any(f.get("op") == "error" for f in session.sent)
    assert proto._log.after(-1) == []
    assert state.get_reply_seq() == 0


@pytest.mark.asyncio
async def test_cancelled_detail_emits_no_frame(tmp_path):
    state = StateStore(str(tmp_path))
    session = FakeSession()
    proto = ProtocolHandler(state, FailBackend("cancelled"))
    proto.bind_session(session)
    ack = await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "hi",
        },
        "a" * 52,
    )
    assert ack["ok"] is True
    await proto.wait_idle()
    assert session.sent == []
    assert state.get_reply_seq() == 0


@pytest.mark.asyncio
async def test_leaky_exception_text_not_on_wire(tmp_path):
    state = StateStore(str(tmp_path))
    session = FakeSession()
    proto = ProtocolHandler(state, FailBackend("Cannot connect to host 10.0.0.1"))
    proto.bind_session(session)
    await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "hi",
        },
        "a" * 52,
    )
    await proto.wait_idle()
    errors = [f for f in session.sent if f.get("op") == "error"]
    assert len(errors) == 1
    assert errors[0]["detail"] == "gateway_error"
    dumped = json.dumps(errors[0])
    assert "Cannot connect" not in dumped
    assert "10.0.0.1" not in dumped


@pytest.mark.asyncio
async def test_empty_complete_sends_no_text_not_error(tmp_path):
    state = StateStore(str(tmp_path))
    session = FakeSession()
    proto = ProtocolHandler(state, EmptyBackend())
    proto.bind_session(session)
    await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": "hi",
        },
        "a" * 52,
    )
    await proto.wait_idle()
    replies = [f for f in session.sent if f.get("op") == "reply"]
    assert len(replies) == 1
    assert replies[0]["text"] == "(no text)"
    assert replies[0]["from"] == "Echo"
    assert not any(f.get("op") == "error" for f in session.sent)


@pytest.mark.asyncio
async def test_cancel_all_tasks_not_just_last(tmp_path):
    state = StateStore(str(tmp_path))
    session = FakeSession()
    backend = HangBackend()
    proto = ProtocolHandler(state, backend)
    proto.bind_session(session)
    for _ in range(2):
        ack = await proto.handle(
            {
                "v": 1,
                "op": "send",
                "id": str(uuid.uuid4()),
                "from": "me",
                "text": "hi",
            },
            "a" * 52,
        )
        assert ack["ok"] is True
    await asyncio.wait_for(backend.first_in.wait(), 2)
    assert len(proto._agent_tasks) == 2
    await asyncio.wait_for(proto.cancel_agent(), 2)
    await asyncio.wait_for(proto.wait_idle(), 2)
    await asyncio.sleep(0)
    assert all(task.done() for task in proto._agent_tasks)
    assert session.sent == []
    assert state.get_reply_seq() == 0
    assert backend.cancelled == 1


@pytest.mark.asyncio
async def test_kick_after_send_no_reply_or_error(tmp_path):
    gw = FakeGrokGateway(mode="hang")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=5)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        ack = await proto.handle(
            {
                "v": 1,
                "op": "send",
                "id": str(uuid.uuid4()),
                "from": "me",
                "text": "hi",
                "agentId": ADA_ID,
            },
            "a" * 52,
        )
        assert ack["ok"] is True
        await asyncio.wait_for(gw.hung.wait(), 2)
        await proto.cancel_agent()
        await proto.wait_idle()
        assert session.sent == []
        assert not any(f.get("op") in {"reply", "error"} for f in session.sent)
        assert state.get_reply_seq() == 0
    finally:
        gw.hang.set()
        await runner.cleanup()


@pytest.mark.asyncio
async def test_grokbot_reply_from_name_for_not_second_list(tmp_path):
    gw = FakeGrokGateway(mode="live")
    gw.list_fail_after = 1
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        ack = await proto.handle(
            {
                "v": 1,
                "op": "send",
                "id": str(uuid.uuid4()),
                "from": "me",
                "text": "hi",
                "agentId": ADA_ID,
            },
            "a" * 52,
        )
        assert ack["ok"] is True
        await proto.wait_idle()
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert len(replies) == 2
        assert [r["text"] for r in replies] == [
            "Hello from Ada",
            "Second bubble",
        ]
        assert [r["seq"] for r in replies] == [0, 1]
        assert replies[0]["from"] == "Ada"
        assert replies[1]["from"] == "Ada"
        assert replies[0]["agentId"] == ADA_ID
        assert replies[0]["inReplyTo"] == ack["echoId"]
        assert replies[1]["inReplyTo"] == ack["echoId"]
        assert not any(f.get("op") == "error" for f in session.sent)
        assert gw.list_calls == 1
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_grokbot_tool_only_protocol_no_text_no_error(tmp_path):
    gw = FakeGrokGateway(mode="tool_only")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=0.35, poll_sec=0.05)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        await proto.handle(
            {
                "v": 1,
                "op": "send",
                "id": str(uuid.uuid4()),
                "from": "me",
                "text": "hi",
                "agentId": ADA_ID,
            },
            "a" * 52,
        )
        await proto.wait_idle()
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert len(replies) == 1
        assert replies[0]["text"] == "(no text)"
        assert replies[0]["from"] == "Ada"
        assert not any(f.get("op") == "error" for f in session.sent)
    finally:
        await runner.cleanup()


async def _grok_send(proto: ProtocolHandler, text: str = "hi") -> dict:
    return await proto.handle(
        {
            "v": 1,
            "op": "send",
            "id": str(uuid.uuid4()),
            "from": "me",
            "text": text,
            "agentId": ADA_ID,
        },
        "a" * 52,
    )


@pytest.mark.asyncio
async def test_grokbot_staged_two_op_replies_incrementing_seq(tmp_path):
    gw = FakeGrokGateway(mode="staged")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        ack = await _grok_send(proto)
        assert ack["ok"] is True
        await proto.wait_idle()
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert [r["text"] for r in replies] == ["I'll check.", "Here's the report."]
        assert [r["seq"] for r in replies] == [0, 1]
        assert all(r["from"] == "Ada" for r in replies)
        assert all(r["inReplyTo"] == ack["echoId"] for r in replies)
        assert all(r.get("live") is True for r in replies)
        assert not any(f.get("op") == "error" for f in session.sent)
        assert state.get_reply_seq() == 2
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_grokbot_quiet_then_late_two_replies(tmp_path):
    gw = FakeGrokGateway(mode="quiet_late")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        ack = await _grok_send(proto)
        await proto.wait_idle()
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert [r["text"] for r in replies] == ["I'll check.", "Here's the report."]
        assert [r["seq"] for r in replies] == [0, 1]
        assert all(r["inReplyTo"] == ack["echoId"] for r in replies)
        assert gw.tail_polls >= 9
        assert not any(f.get("op") == "error" for f in session.sent)
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_grokbot_cut_streaming_yields_inflight_reply(tmp_path):
    gw = FakeGrokGateway(mode="cut_streaming")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        await _grok_send(proto)
        await proto.wait_idle()
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert [r["text"] for r in replies] == ["I'll check.", "Here's the report."]
        assert not any(f.get("op") == "error" for f in session.sent)
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_grokbot_timeout_after_bubbles_no_error(tmp_path):
    gw = FakeGrokGateway(mode="staged_no_cut")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=0.35, poll_sec=0.05)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        await _grok_send(proto)
        await proto.wait_idle()
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert [r["text"] for r in replies] == ["I'll check.", "Here's the report."]
        assert not any(f.get("op") == "error" for f in session.sent)
        assert state.get_reply_seq() == 2
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_cancel_after_first_bubble_no_error(tmp_path):
    gw = FakeGrokGateway(mode="first_then_hang")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=5, poll_sec=0.05)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        ack = await _grok_send(proto)
        await asyncio.wait_for(gw.hung.wait(), 2)
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert [r["text"] for r in replies] == ["I'll check."]
        await proto.cancel_agent()
        await proto.wait_idle()
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert [r["text"] for r in replies] == ["I'll check."]
        assert replies[0]["inReplyTo"] == ack["echoId"]
        assert not any(f.get("op") == "error" for f in session.sent)
        assert state.get_reply_seq() == 1
    finally:
        gw.hang.set()
        await runner.cleanup()


@pytest.mark.asyncio
async def test_agent_lock_holds_until_watch_ends(tmp_path):
    gw = FakeGrokGateway(mode="staged")
    runner, port = await start_fake_gateway(gw)
    try:
        state = StateStore(str(tmp_path / "state"))
        session = FakeSession()
        backend = make_backend(tmp_path, port, timeout_sec=2, poll_sec=0.05)
        proto = ProtocolHandler(state, backend)
        proto.bind_session(session)
        ack1 = await _grok_send(proto, "first")
        for _ in range(50):
            if gw.sends:
                break
            await asyncio.sleep(0.02)
        assert len(gw.sends) == 1
        ack2 = await _grok_send(proto, "second")
        await asyncio.sleep(0.12)
        assert len(gw.sends) == 1
        await proto.wait_idle()
        assert len(gw.sends) == 2
        replies = [f for f in session.sent if f.get("op") == "reply"]
        assert len(replies) == 4
        first_turn = [r for r in replies if r["inReplyTo"] == ack1["echoId"]]
        second_turn = [r for r in replies if r["inReplyTo"] == ack2["echoId"]]
        assert [r["text"] for r in first_turn] == ["I'll check.", "Here's the report."]
        assert [r["text"] for r in second_turn] == ["I'll check.", "Here's the report."]
        assert [r["seq"] for r in replies] == [0, 1, 2, 3]
    finally:
        await runner.cleanup()
