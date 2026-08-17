"""Tests for loopback WebSocket session, consume-once pair, and start rules."""

import asyncio
import json
import uuid

import aiohttp
import pytest

from agent import EchoBackend
from config import Config
from main import prepare_pairing_state
from mint import PHONE_CROCKFORD_8_REGEX, mint_invite
from protocol import ProtocolHandler
from server import GlassServer
from session import SessionConn, SessionServer
from session_log import SessionLog
from state import StateStore

PHONE = "a" * 52
PHONE_B = "b" * 52


def _config(tmp_path, **overrides) -> Config:
    kw = {
        "pair_username": "u",
        "pair_password": "p",
        "data_dir": str(tmp_path),
        "http_bind": "127.0.0.1",
        "port": 0,
        "session_bind": "127.0.0.1",
        "session_port": 0,
        "session_path": "/session",
        "session_max_candidates": 8,
        "invite_ttl_seconds": 300,
    }
    kw.update(overrides)
    return Config(**kw)


class PeerEnv:
    def __init__(self, tmp_path, backend=None, pair_hello_timeout=15.0, **cfg):
        self.config = _config(tmp_path, **cfg)
        self.state = StateStore(str(tmp_path))
        invite = mint_invite(300)
        self.state.set_invite(invite)
        self.backend = backend or EchoBackend()
        self.protocol = ProtocolHandler(self.state, self.backend)
        self.session = SessionServer(
            self.config,
            self.state,
            self.protocol,
            pair_hello_timeout=pair_hello_timeout,
        )
        self.protocol.bind_session(self.session)
        self.http = GlassServer(
            self.config, self.state, self.session, backend_name=self.backend.name
        )

    @property
    def ws_url(self) -> str:
        return f"ws://127.0.0.1:{self.session.port}{self.session.path}"

    @property
    def code(self) -> str:
        return self.state.get_invite().code


@pytest.fixture
async def env(tmp_path):
    peer = PeerEnv(tmp_path, pair_hello_timeout=15.0)
    await peer.session.start()
    await peer.http.start()
    try:
        yield peer
    finally:
        await peer.http.stop()
        await peer.session.stop()


async def _pair_and_hello(ws, code: str, peer: str = PHONE) -> dict:
    await ws.send_json({"v": 1, "code": code})
    ack = await ws.receive_json()
    assert ack["ok"] is True
    hid = str(uuid.uuid4())
    await ws.send_json({"v": 1, "op": "hello", "id": hid, "peer": peer})
    return await ws.receive_json()


@pytest.mark.asyncio
async def test_hello_without_pair_unpaired_does_not_persist(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        hid = str(uuid.uuid4())
        await ws.send_json({"v": 1, "op": "hello", "id": hid, "peer": PHONE})
        resp = await ws.receive_json()
        assert resp["ok"] is False
        assert resp["error"] == "unpaired"
        assert resp["id"] == hid
    assert env.state.get_phone_peer() is None
    assert not env.state.is_code_consumed()


@pytest.mark.asyncio
async def test_pair_hello_consumes_and_stores_peer(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        hello = await _pair_and_hello(ws, env.code)
        assert hello["ok"] is True
        assert hello["op"] == "hello"
        assert hello["sessionId"] == env.state.get_session_id()
        assert hello["seq"] == 0
    assert env.state.get_phone_peer() == PHONE
    assert env.state.is_code_consumed()


@pytest.mark.asyncio
async def test_reconnect_hello_requires_phone_peer(env):
    env.state.mark_paired(PHONE)
    async with aiohttp.ClientSession() as sess:
        async with sess.ws_connect(env.ws_url) as ws:
            hid = str(uuid.uuid4())
            await ws.send_json({"v": 1, "op": "hello", "id": hid, "peer": PHONE})
            hello = await ws.receive_json()
            assert hello["ok"] is True
            assert hello["sessionId"]
        async with sess.ws_connect(env.ws_url) as ws:
            await ws.send_json(
                {"v": 1, "op": "hello", "id": str(uuid.uuid4()), "peer": PHONE_B}
            )
            resp = await ws.receive_json()
            assert resp["ok"] is False
            assert resp["error"] == "wrong_peer"
    assert env.state.get_phone_peer() == PHONE


@pytest.mark.asyncio
async def test_first_frame_neither_pair_nor_hello_rejected(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        await ws.send_json(
            {"v": 1, "op": "send", "id": "s", "from": "jamie", "text": "x"}
        )
        resp = await ws.receive_json()
        assert resp["ok"] is False
        assert resp["error"] == "rejected"


@pytest.mark.asyncio
async def test_wrong_code_rejected(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        await ws.send_json({"v": 1, "code": "DEADBEEF"})
        resp = await ws.receive_json()
        assert resp["ok"] is False
        assert resp["error"] == "rejected"
    assert not env.state.is_code_consumed()


@pytest.mark.asyncio
async def test_second_pair_after_consume_rejected(env):
    async with aiohttp.ClientSession() as sess:
        async with sess.ws_connect(env.ws_url) as ws:
            hello = await _pair_and_hello(ws, env.code)
            assert hello["ok"] is True
        async with sess.ws_connect(env.ws_url) as ws:
            await ws.send_json({"v": 1, "code": env.code})
            resp = await ws.receive_json()
            assert resp["ok"] is False
            assert resp["error"] == "rejected"


@pytest.mark.asyncio
async def test_echo_send_complete_and_seq(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        hello = await _pair_and_hello(ws, env.code)
        assert hello["seq"] == 0
        sid = str(uuid.uuid4())
        await ws.send_json(
            {
                "v": 1,
                "op": "send",
                "id": sid,
                "from": "jamie",
                "text": "hi",
                "at": "2024-01-01T00:00:00Z",
            }
        )
        ack = await ws.receive_json()
        assert ack["ok"] is True
        assert ack["id"] == sid
        assert ack["echoId"]
        reply = await asyncio.wait_for(ws.receive_json(), 2)
        assert reply["op"] == "reply"
        assert reply["seq"] == 0
        assert reply["text"] == "echo: hi"
        assert reply["inReplyTo"] == ack["echoId"]


@pytest.mark.asyncio
async def test_no_live_ws_queues_reply_and_catchup(tmp_path):
    started = asyncio.Event()
    gate = asyncio.Event()

    class GateBackend(EchoBackend):
        async def complete(self, *, text, agent_id, phone_peer):
            started.set()
            await gate.wait()
            return "echo: late"

    peer = PeerEnv(tmp_path, backend=GateBackend(), pair_hello_timeout=15)
    await peer.session.start()
    try:
        async with (
            aiohttp.ClientSession() as sess,
            sess.ws_connect(peer.ws_url) as ws,
        ):
            hello = await _pair_and_hello(ws, peer.code)
            assert hello["ok"] is True
            await ws.send_json(
                {
                    "v": 1,
                    "op": "send",
                    "id": str(uuid.uuid4()),
                    "from": "jamie",
                    "text": "late",
                }
            )
            ack = await ws.receive_json()
            assert ack["ok"] is True
            await asyncio.wait_for(started.wait(), 2)
            await ws.close()

        gate.set()
        await peer.protocol.wait_idle()
        assert peer.state.get_reply_seq() == 1
        queued = peer.protocol._log.after(-1)
        assert len(queued) == 1
        assert queued[0]["text"] == "echo: late"

        async with (
            aiohttp.ClientSession() as sess,
            sess.ws_connect(peer.ws_url) as ws,
        ):
            await ws.send_json(
                {"v": 1, "op": "hello", "id": str(uuid.uuid4()), "peer": PHONE}
            )
            hello = await ws.receive_json()
            assert hello["ok"] is True
            assert hello["seq"] == 1
            with pytest.raises(asyncio.TimeoutError):
                await asyncio.wait_for(ws.receive_json(), 0.3)

        async with (
            aiohttp.ClientSession() as sess,
            sess.ws_connect(peer.ws_url) as ws,
        ):
            await ws.send_json(
                {
                    "v": 1,
                    "op": "hello",
                    "id": str(uuid.uuid4()),
                    "peer": PHONE,
                    "lastSeenSeq": -1,
                }
            )
            hello = await ws.receive_json()
            assert hello["ok"] is True
            assert hello["seq"] == 1
            catchup = await asyncio.wait_for(ws.receive_json(), 2)
            assert catchup["op"] == "reply"
            assert catchup["seq"] == 0
            assert catchup["text"] == "echo: late"
            assert catchup["live"] is False
            assert catchup["catchUp"] is True
            assert catchup["id"] == queued[0]["id"]
    finally:
        await peer.session.stop()


@pytest.mark.asyncio
async def test_paired_expired_invite_process_start_reconnect(tmp_path):
    store = StateStore(str(tmp_path))
    invite = mint_invite(60)
    store.set_invite(invite)
    store.mark_paired(PHONE)
    path = tmp_path / "state.json"
    data = json.loads(path.read_text())
    data["invite"]["exp"] = "2000-01-01T00:00:00Z"
    data["code_consumed"] = True
    path.write_text(json.dumps(data))

    store = StateStore(str(tmp_path))
    old_peer = store.get_invite().peer
    old_phone = store.get_phone_peer()
    old_sid = store.get_session_id()
    minted = prepare_pairing_state(store, 300)
    assert minted is False
    assert store.get_invite().peer == old_peer
    assert store.get_phone_peer() == old_phone
    assert store.is_code_consumed()
    assert store.get_session_id() == old_sid

    config = _config(tmp_path)
    backend = EchoBackend()
    proto = ProtocolHandler(store, backend)
    session = SessionServer(config, store, proto, pair_hello_timeout=15)
    proto.bind_session(session)
    await session.start()
    try:
        async with (
            aiohttp.ClientSession() as sess,
            sess.ws_connect(f"ws://127.0.0.1:{session.port}/session") as ws,
        ):
            await ws.send_json(
                {"v": 1, "op": "hello", "id": str(uuid.uuid4()), "peer": PHONE}
            )
            hello = await ws.receive_json()
            assert hello["ok"] is True
            assert hello["sessionId"] == store.get_session_id()
    finally:
        await session.stop()


@pytest.mark.asyncio
async def test_remint_while_pair_ok_candidate_rejects_hello(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        await ws.send_json({"v": 1, "code": env.code})
        ack = await ws.receive_json()
        assert ack["ok"] is True

        new_invite = mint_invite(300)
        env.state.set_invite(new_invite)
        await env.session.kick()

        try:
            await ws.send_json(
                {
                    "v": 1,
                    "op": "hello",
                    "id": str(uuid.uuid4()),
                    "peer": PHONE,
                }
            )
            msg = await asyncio.wait_for(ws.receive(), 1)
            if msg.type == aiohttp.WSMsgType.TEXT:
                body = json.loads(msg.data)
                assert body.get("ok") is False
                assert body.get("error") == "rejected"
        except (
            asyncio.TimeoutError,
            aiohttp.ClientError,
            ConnectionResetError,
        ):
            pass

    assert env.state.get_phone_peer() is None
    assert not env.state.is_code_consumed()
    assert env.state.get_invite().code == new_invite.code


@pytest.mark.asyncio
async def test_two_parallel_pair_hello_one_phone_peer(env):
    async with aiohttp.ClientSession() as sess:
        ws1 = await sess.ws_connect(env.ws_url)
        ws2 = await sess.ws_connect(env.ws_url)
        try:
            await ws1.send_json({"v": 1, "code": env.code})
            await ws2.send_json({"v": 1, "code": env.code})
            a1 = await ws1.receive_json()
            a2 = await ws2.receive_json()
            assert a1["ok"] and a2["ok"]

            async def hello(ws, peer):
                await ws.send_json(
                    {"v": 1, "op": "hello", "id": str(uuid.uuid4()), "peer": peer}
                )
                return await ws.receive_json()

            r1, r2 = await asyncio.gather(hello(ws1, PHONE), hello(ws2, PHONE_B))
            oks = [r for r in (r1, r2) if r.get("ok")]
            errs = [r for r in (r1, r2) if not r.get("ok")]
            assert len(oks) == 1
            assert len(errs) == 1
            assert errs[0]["error"] in {"rejected", "wrong_peer"}
            assert env.state.get_phone_peer() in {PHONE, PHONE_B}
        finally:
            await ws1.close()
            await ws2.close()


@pytest.mark.asyncio
async def test_ninth_upgrade_503_live_stays(tmp_path):
    peer = PeerEnv(tmp_path, pair_hello_timeout=30)
    await peer.session.start()
    sockets = []
    try:
        async with aiohttp.ClientSession() as sess:
            live = await sess.ws_connect(peer.ws_url)
            sockets.append(live)
            hello = await _pair_and_hello(live, peer.code)
            assert hello["ok"] is True

            for _ in range(8):
                sockets.append(await sess.ws_connect(peer.ws_url))

            with pytest.raises(aiohttp.WSServerHandshakeError) as ei:
                await sess.ws_connect(peer.ws_url)
            assert ei.value.status == 503

            assert peer.state.get_phone_peer() == PHONE
            assert peer.session.has_live_session()
            await live.send_json({"v": 1, "op": "ping", "id": "keep"})
            pong = await live.receive_json()
            assert pong["op"] == "pong"
    finally:
        for ws in sockets:
            await ws.close()
        await peer.session.stop()


@pytest.mark.asyncio
async def test_pair_on_session_port_is_404(env):
    async with (
        aiohttp.ClientSession() as sess,
        sess.get(f"http://127.0.0.1:{env.session.port}/pair") as resp,
    ):
        assert resp.status == 404


@pytest.mark.asyncio
async def test_session_on_operator_port_is_404(env):
    async with (
        aiohttp.ClientSession() as sess,
        sess.get(f"http://127.0.0.1:{env.http.port}/session") as resp,
    ):
        assert resp.status == 404


@pytest.mark.asyncio
async def test_non_upgrade_session_426_no_invite_secrets(env):
    async with (
        aiohttp.ClientSession() as sess,
        sess.get(f"http://127.0.0.1:{env.session.port}/session") as resp,
    ):
        assert resp.status == 426
        body = await resp.text()
        assert env.code not in body
        invite = env.state.get_invite()
        assert invite.peer not in body
        assert invite.pub not in body
        assert "invite" not in body.lower()


@pytest.mark.asyncio
async def test_session_trailing_slash_is_same_route(env):
    async with (
        aiohttp.ClientSession() as sess,
        sess.ws_connect(env.ws_url + "/") as ws,
    ):
        hello = await _pair_and_hello(ws, env.code)
        assert hello["ok"] is True


@pytest.mark.asyncio
async def test_new_accept_does_not_preempt_live(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as live:
        hello = await _pair_and_hello(live, env.code)
        assert hello["ok"] is True
        async with sess.ws_connect(env.ws_url) as cand:
            await cand.send_json(
                {"v": 1, "op": "hello", "id": str(uuid.uuid4()), "peer": PHONE}
            )
            resp = await cand.receive_json()
            assert resp["ok"] is False
            assert resp["error"] == "rejected"
        assert env.session.has_live_session()
        await live.send_json({"v": 1, "op": "ping", "id": "x"})
        assert (await live.receive_json())["op"] == "pong"


@pytest.mark.asyncio
async def test_pair_timeout_leaves_code_unconsumed(tmp_path):
    peer = PeerEnv(tmp_path, pair_hello_timeout=0.2)
    await peer.session.start()
    try:
        async with (
            aiohttp.ClientSession() as sess,
            sess.ws_connect(peer.ws_url) as ws,
        ):
            await ws.send_json({"v": 1, "code": peer.code})
            ack = await ws.receive_json()
            assert ack["ok"] is True
            closed = await asyncio.wait_for(ws.receive(), 2)
            assert closed.type in {
                aiohttp.WSMsgType.CLOSE,
                aiohttp.WSMsgType.CLOSED,
            }
        assert not peer.state.is_code_consumed()
        assert peer.state.get_phone_peer() is None
    finally:
        await peer.session.stop()


@pytest.mark.asyncio
async def test_hello_timer_does_not_close_live(tmp_path):
    peer = PeerEnv(tmp_path, pair_hello_timeout=0.15)
    await peer.session.start()
    try:
        async with (
            aiohttp.ClientSession() as sess,
            sess.ws_connect(peer.ws_url) as ws,
        ):
            hello = await _pair_and_hello(ws, peer.code)
            assert hello["ok"] is True
            await asyncio.sleep(0.3)
            assert peer.session.has_live_session()
            await ws.send_json({"v": 1, "op": "ping", "id": "t"})
            pong = await ws.receive_json()
            assert pong["op"] == "pong"
    finally:
        await peer.session.stop()


@pytest.mark.asyncio
async def test_non_string_pair_code_rejected(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        await ws.send_json({"v": 1, "code": 12345678})
        resp = await ws.receive_json()
        assert resp["ok"] is False
        assert resp["error"] == "rejected"
    assert not env.state.is_code_consumed()


def test_legacy_paired_state_treats_invite_as_consumed(tmp_path):
    invite = mint_invite(300)
    path = tmp_path / "state.json"
    path.write_text(
        json.dumps(
            {
                "invite": {
                    "version": invite.version,
                    "peer": invite.peer,
                    "pub": invite.pub,
                    "code": invite.code,
                    "exp": invite.exp,
                },
                "phone_peer": PHONE,
                "paired_at": "2024-01-01T00:00:00Z",
            }
        )
    )
    store = StateStore(str(tmp_path))
    assert store.get_phone_peer() == PHONE
    assert store.is_code_consumed()


def test_bind_nonlocal_refused_without_escape(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_SESSION_BIND", "0.0.0.0")
    monkeypatch.delenv("GLASS_SESSION_ALLOW_NONLOCAL", raising=False)
    with pytest.raises(ValueError, match="GLASS_SESSION_BIND"):
        Config.from_env()


def test_bind_nonlocal_allowed_with_escape(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_SESSION_BIND", "0.0.0.0")
    monkeypatch.setenv("GLASS_SESSION_ALLOW_NONLOCAL", "1")
    cfg = Config.from_env()
    assert cfg.session_bind == "0.0.0.0"


def test_public_wss_operator_enphi_allowed(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_PUBLIC_WSS_URL", "wss://glass.enphi.net/session")
    cfg = Config.from_env()
    assert cfg.public_wss_url == "wss://glass.enphi.net/session"


def test_public_ws_lan_allowed_loopback_refused(monkeypatch, tmp_path):
    monkeypatch.setenv("GLASS_PAIR_USERNAME", "u")
    monkeypatch.setenv("GLASS_PAIR_PASSWORD", "p")
    monkeypatch.setenv("GLASS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GLASS_PUBLIC_WSS_URL", "ws://192.168.1.200:8711/session")
    cfg = Config.from_env()
    assert cfg.public_wss_url == "ws://192.168.1.200:8711/session"
    monkeypatch.setenv("GLASS_PUBLIC_WSS_URL", "ws://127.0.0.1:8711/session")
    with pytest.raises(ValueError, match="127.0.0.1"):
        Config.from_env()


def test_mint_codes_match_phone_crockford():
    assert PHONE_CROCKFORD_8_REGEX.match("F41XS71T")
    assert PHONE_CROCKFORD_8_REGEX.match("01ABCDEF")
    assert PHONE_CROCKFORD_8_REGEX.match("01A1B0C2")
    assert not PHONE_CROCKFORD_8_REGEX.match("ILOU1234")
    for _ in range(200):
        invite = mint_invite()
        assert PHONE_CROCKFORD_8_REGEX.match(invite.code)


@pytest.mark.asyncio
async def test_http_remint_kicks_live(env):
    async with aiohttp.ClientSession() as sess, sess.ws_connect(env.ws_url) as ws:
        hello = await _pair_and_hello(ws, env.code)
        assert hello["ok"] is True
        auth = aiohttp.BasicAuth("u", "p")
        async with sess.post(
            f"http://127.0.0.1:{env.http.port}/pair", auth=auth
        ) as resp:
            assert resp.status == 200
            body = await resp.json()
            assert "invite" in body
        closed = await asyncio.wait_for(ws.receive(), 2)
        assert closed.type in {
            aiohttp.WSMsgType.CLOSE,
            aiohttp.WSMsgType.CLOSED,
        }
    assert env.state.get_phone_peer() is None
    assert not env.state.is_code_consumed()


def _seed_reply(log: SessionLog, text: str, echo: str = "echo") -> dict:
    stored = log.allocate_and_append(
        {
            "id": str(uuid.uuid4()),
            "from": "Echo",
            "agentId": "echo",
            "text": text,
            "at": "2026-08-17T12:00:00.000+00:00",
            "inReplyTo": echo,
        }
    )
    assert stored is not None
    return stored


def _make_server(tmp_path) -> tuple[StateStore, SessionLog, SessionServer]:
    config = _config(tmp_path)
    state = StateStore(str(tmp_path))
    invite = mint_invite(300)
    state.set_invite(invite)
    state.mark_paired(PHONE)
    proto = ProtocolHandler(state, EchoBackend())
    session = SessionServer(config, state, proto, pair_hello_timeout=15)
    proto.bind_session(session)
    return state, proto._log, session


@pytest.mark.asyncio
async def test_hello_last_seen_filters_and_mismatch_skips(tmp_path):
    peer = PeerEnv(tmp_path)
    for text in ("a", "b", "c"):
        _seed_reply(peer.protocol._log, text)
    assert peer.state.get_reply_seq() == 3
    await peer.session.start()
    try:
        async with aiohttp.ClientSession() as sess:
            async with sess.ws_connect(peer.ws_url) as ws:
                hello = await _pair_and_hello(ws, peer.code)
                assert hello["ok"] is True
            async with sess.ws_connect(peer.ws_url) as ws:
                await ws.send_json(
                    {
                        "v": 1,
                        "op": "hello",
                        "id": str(uuid.uuid4()),
                        "peer": PHONE,
                        "lastSeenSeq": 0,
                    }
                )
                hello = await ws.receive_json()
                assert hello["ok"] is True
                first = await asyncio.wait_for(ws.receive_json(), 2)
                second = await asyncio.wait_for(ws.receive_json(), 2)
                assert [first["seq"], second["seq"]] == [1, 2]
                assert first["live"] is False and first["catchUp"] is True
                assert second["catchUp"] is True
                with pytest.raises(asyncio.TimeoutError):
                    await asyncio.wait_for(ws.receive_json(), 0.2)
            async with sess.ws_connect(peer.ws_url) as ws:
                await ws.send_json(
                    {
                        "v": 1,
                        "op": "hello",
                        "id": str(uuid.uuid4()),
                        "peer": PHONE,
                        "lastSeenSeq": -1,
                        "sessionId": str(uuid.uuid4()),
                    }
                )
                hello = await ws.receive_json()
                assert hello["ok"] is True
                with pytest.raises(asyncio.TimeoutError):
                    await asyncio.wait_for(ws.receive_json(), 0.2)
    finally:
        await peer.session.stop()


@pytest.mark.asyncio
async def test_http_remint_wipes_session_log(env):
    _seed_reply(env.protocol._log, "keep-me-not")
    assert env.protocol._log.after(-1)
    assert env.protocol._log.path.exists()
    async with aiohttp.ClientSession() as sess:
        auth = aiohttp.BasicAuth("u", "p")
        async with sess.post(
            f"http://127.0.0.1:{env.http.port}/pair", auth=auth
        ) as resp:
            assert resp.status == 200
    assert env.state.get_reply_seq() == 0
    assert env.protocol._log.after(-1) == []
    assert not env.protocol._log.path.exists()


def test_paired_restart_keeps_session_and_log(tmp_path):
    store = StateStore(str(tmp_path))
    invite = mint_invite(300)
    store.set_invite(invite)
    store.mark_paired(PHONE)
    log = SessionLog(store)
    store.bind_session_log(log.clear)
    row = _seed_reply(log, "still here")
    old_sid = store.get_session_id()
    old_seq = store.get_reply_seq()
    minted = prepare_pairing_state(store, 300, log)
    assert minted is False
    assert store.get_session_id() == old_sid
    assert store.get_reply_seq() == old_seq
    kept = log.after(-1)
    assert len(kept) == 1
    assert kept[0]["id"] == row["id"]
    assert kept[0]["text"] == "still here"


def test_unpaired_start_wipes_log(tmp_path):
    store = StateStore(str(tmp_path))
    store.set_invite(mint_invite(300))
    log = SessionLog(store)
    store.bind_session_log(log.clear)
    _seed_reply(log, "gone")
    assert log.after(-1)
    minted = prepare_pairing_state(store, 300, log)
    assert minted is False
    assert log.after(-1) == []
    assert not log.path.exists()
    assert store.get_reply_seq() == 0


class _BarrierWS:
    def __init__(self):
        self.closed = False
        self.sent: list[dict] = []
        self.hello_started = asyncio.Event()
        self.hello_gate = asyncio.Event()
        self._first = True

    async def send_json(self, obj: dict) -> None:
        if self._first:
            self._first = False
            self.hello_started.set()
            await self.hello_gate.wait()
        self.sent.append(obj)

    async def close(self) -> None:
        self.closed = True


@pytest.mark.asyncio
async def test_hello_barrier_live_reply_waits_for_snapshot(tmp_path):
    _state, log, session = _make_server(tmp_path)
    seeded = _seed_reply(log, "queued")
    dummy = _BarrierWS()
    conn = SessionConn(ws=dummy)

    async def inject():
        await dummy.hello_started.wait()
        assert session.has_live_session()
        assert not session._outbound_ready.is_set()
        live_row = _seed_reply(log, "live-now")
        frame = {**live_row, "v": 1, "op": "reply", "live": True}
        ok = await session.send_json(frame)
        assert ok is True
        return live_row

    inject_task = asyncio.create_task(inject())
    hello_task = asyncio.create_task(
        session._handle_hello(
            conn,
            {
                "v": 1,
                "op": "hello",
                "id": "h1",
                "peer": PHONE,
                "lastSeenSeq": -1,
            },
        )
    )
    await dummy.hello_started.wait()
    for _ in range(20):
        await asyncio.sleep(0)
    assert dummy.sent == []
    assert not inject_task.done()
    dummy.hello_gate.set()
    await hello_task
    live_row = await inject_task
    assert dummy.sent[0]["op"] == "hello"
    assert dummy.sent[0]["ok"] is True
    catchups = [f for f in dummy.sent if f.get("catchUp") is True]
    assert len(catchups) == 1
    assert catchups[0]["seq"] == seeded["seq"]
    assert catchups[0]["live"] is False
    assert dummy.sent[-1]["live"] is True
    assert dummy.sent[-1]["seq"] == live_row["seq"]
    assert dummy.sent[-1]["text"] == "live-now"
    hello_idx = 0
    catch_idx = dummy.sent.index(catchups[0])
    live_idx = len(dummy.sent) - 1
    assert hello_idx < catch_idx < live_idx


@pytest.mark.asyncio
async def test_send_json_no_live_does_not_wait_and_kick_unblocks(tmp_path):
    _state, _log, session = _make_server(tmp_path)
    assert not session._outbound_ready.is_set()
    sent = await asyncio.wait_for(session.send_json({"op": "reply"}), 0.2)
    assert sent is False

    dummy = _BarrierWS()
    conn = SessionConn(ws=dummy)

    async def waiter():
        await dummy.hello_started.wait()
        return await session.send_json({"v": 1, "op": "reply", "seq": 99})

    wait_task = asyncio.create_task(waiter())
    hello_task = asyncio.create_task(
        session._handle_hello(
            conn,
            {"v": 1, "op": "hello", "id": "h2", "peer": PHONE, "lastSeenSeq": -1},
        )
    )
    await dummy.hello_started.wait()
    for _ in range(20):
        await asyncio.sleep(0)
    assert not wait_task.done()
    await session.kick()
    assert await wait_task is False
    dummy.hello_gate.set()
    await hello_task
    assert not session._outbound_ready.is_set()
    assert session._live is None
