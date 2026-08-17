"""Loopback WebSocket session accept (live vs candidates)."""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass, field

from aiohttp import WSMsgType, web

from config import Config
from protocol import ProtocolHandler, error_payload
from state import StateStore, codes_equal

logger = logging.getLogger(__name__)

MAX_CANDIDATES = 8
PAIR_HELLO_TIMEOUT_SEC = 15.0


def valid_phone_peer(peer: str) -> bool:
    return isinstance(peer, str) and len(peer) == 52


def bound_port(runner: web.AppRunner) -> int:
    for site in runner.sites:
        server = getattr(site, "_server", None)
        if server is None:
            continue
        socks = getattr(server, "sockets", None) or []
        if socks:
            return int(socks[0].getsockname()[1])
    raise RuntimeError("server has no bound socket")


@dataclass(eq=False)
class SessionConn:
    ws: web.WebSocketResponse
    accepted_code: str | None = None
    helloed: bool = False
    peer: str | None = None
    timer_task: asyncio.Task | None = field(default=None, repr=False)


class SessionServer:
    """aiohttp WS on loopback. At most one live hello'd socket."""

    def __init__(
        self,
        config: Config,
        state: StateStore,
        protocol: ProtocolHandler,
        *,
        pair_hello_timeout: float | None = None,
    ):
        self._bind = config.session_bind
        self._port = config.session_port
        self._path = config.session_path.rstrip("/") or "/session"
        self._max_candidates = config.session_max_candidates or MAX_CANDIDATES
        self._timeout = (
            PAIR_HELLO_TIMEOUT_SEC if pair_hello_timeout is None else pair_hello_timeout
        )
        self._state = state
        self._protocol = protocol
        self._log = protocol._log
        self._app = web.Application()
        self._runner: web.AppRunner | None = None
        self._live: SessionConn | None = None
        self._candidates: set[SessionConn] = set()
        self._upgrade_lock = asyncio.Lock()
        self._hello_lock = asyncio.Lock()
        self._outbound_ready = asyncio.Event()
        self._outbound_gen = 0
        self._setup_routes()

    def _setup_routes(self) -> None:
        self._app.router.add_get(self._path, self._session_handler)
        self._app.router.add_get(self._path + "/", self._session_handler)

    @property
    def port(self) -> int:
        return self._port

    @property
    def path(self) -> str:
        return self._path

    def has_live_session(self) -> bool:
        live = self._live
        return bool(live is not None and live.helloed and not live.ws.closed)

    def candidate_count(self) -> int:
        return len(self._candidates)

    async def start(self) -> None:
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self._bind, self._port)
        await site.start()
        if self._port == 0:
            self._port = bound_port(self._runner)
        logger.info(f"session listen {self._bind}:{self._port} path={self._path}")

    async def stop(self) -> None:
        await self.kick()
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None

    async def kick(self) -> None:
        await self._protocol.cancel_agent()
        async with self._hello_lock:
            victims = list(self._candidates)
            if self._live is not None and self._live not in victims:
                victims.append(self._live)
            n_cand = len(self._candidates)
            self._candidates.clear()
            self._live = None
            self._abandon_outbound()
            for conn in victims:
                conn.accepted_code = None
                conn.helloed = False
                self._cancel_timer(conn)
        logger.info(f"kick closed {n_cand} candidates")
        for conn in victims:
            try:
                await conn.ws.close()
            except Exception:
                pass

    def _live_ok(self, live: SessionConn | None) -> bool:
        return live is not None and live.helloed and not live.ws.closed

    def _abandon_outbound(self) -> None:
        """End this generation so waiting send_json callers return False."""
        self._outbound_gen += 1
        old = self._outbound_ready
        self._outbound_ready = asyncio.Event()
        old.set()

    async def send_json(self, obj: dict) -> bool:
        """Wait only while this generation is live and hello-ok+snapshot have been written."""
        gen = self._outbound_gen
        ev = self._outbound_ready
        live = self._live
        if not self._live_ok(live) or self._outbound_gen != gen:
            return False
        await ev.wait()
        if self._outbound_gen != gen:
            return False
        live = self._live
        if not self._live_ok(live):
            return False
        try:
            await live.ws.send_json(obj)
            return True
        except Exception:
            return False

    async def _session_handler(self, request: web.Request) -> web.StreamResponse:
        if request.headers.get("Upgrade", "").lower() != "websocket":
            return web.Response(status=426, text="WebSocket required")

        async with self._upgrade_lock:
            if len(self._candidates) >= self._max_candidates:
                logger.info("candidate cap refused")
                return web.Response(status=503, text="too many candidates")
            ws = web.WebSocketResponse(heartbeat=30)
            await ws.prepare(request)
            conn = SessionConn(ws=ws)
            self._candidates.add(conn)

        logger.info(f"session upgrade candidate={len(self._candidates)}")
        try:
            self._arm_timer(conn)
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    await self._on_text(conn, msg.data)
                    if ws.closed:
                        break
                elif msg.type in (
                    WSMsgType.ERROR,
                    WSMsgType.CLOSE,
                    WSMsgType.CLOSED,
                ):
                    break
        finally:
            self._cleanup(conn)
        return ws

    def _cleanup(self, conn: SessionConn) -> None:
        self._cancel_timer(conn)
        self._candidates.discard(conn)
        if self._live is conn:
            self._live = None
            self._abandon_outbound()

    def _arm_timer(self, conn: SessionConn) -> None:
        self._cancel_timer(conn)

        async def _fire() -> None:
            try:
                await asyncio.sleep(self._timeout)
                async with self._hello_lock:
                    if conn.helloed:
                        return
                    conn.accepted_code = None
                logger.info("pair/hello timeout")
                try:
                    await conn.ws.close()
                except Exception:
                    pass
            except asyncio.CancelledError:
                return

        conn.timer_task = asyncio.create_task(_fire())

    def _cancel_timer(self, conn: SessionConn) -> None:
        task = conn.timer_task
        conn.timer_task = None
        if task is not None and not task.done():
            task.cancel()

    def _promote(self, conn: SessionConn, peer: str) -> None:
        conn.helloed = True
        conn.peer = peer
        self._candidates.discard(conn)
        self._live = conn
        self._cancel_timer(conn)

    async def _on_text(self, conn: SessionConn, data: str) -> None:
        try:
            msg = json.loads(data)
        except json.JSONDecodeError:
            await self._reject(conn, "invalid_json")
            return
        if not isinstance(msg, dict):
            await self._reject(conn, "rejected")
            return

        if not conn.helloed:
            await self._handle_auth(conn, msg)
            return

        resp = await self._protocol.handle(msg, conn.peer or "")
        if resp is not None:
            try:
                await conn.ws.send_json(resp)
            except Exception:
                pass

    async def _handle_auth(self, conn: SessionConn, msg: dict) -> None:
        op = msg.get("op")
        if conn.accepted_code is None:
            if op is None and "code" in msg:
                await self._handle_pair(conn, msg)
                return
            if op == "hello":
                await self._handle_hello(conn, msg)
                return
            await self._reject(conn, "rejected", msg.get("id"))
            return
        if op == "hello":
            await self._handle_hello(conn, msg)
            return
        await self._reject(conn, "rejected", msg.get("id"))

    def _pair_error(self, code: str) -> str | None:
        invite = self._state.get_invite()
        if invite is None:
            return "missing"
        if self._state.is_code_consumed():
            return "consumed"
        if not codes_equal(code, invite.code):
            return "mismatch"
        # Paired leftover QRs are rejected via code_consumed above.
        if self._state.get_phone_peer() is None and invite.is_expired:
            return "expired"
        return None

    async def _handle_pair(self, conn: SessionConn, msg: dict) -> None:
        if msg.get("v", 1) != 1:
            await self._reject(conn, "unsupported_version")
            return
        code = msg.get("code")
        if not isinstance(code, str):
            await self._reject(conn, "rejected")
            return
        err = self._pair_error(code)
        if err:
            logger.info(f"pair reject reason={err}")
            await self._reject(conn, "rejected")
            return
        invite = self._state.get_invite()
        conn.accepted_code = invite.code if invite else code
        self._arm_timer(conn)
        logger.info("pair ok")
        await conn.ws.send_json({"v": 1, "ok": True})

    async def _handle_hello(self, conn: SessionConn, msg: dict) -> None:
        req_id = msg.get("id")
        if msg.get("v", 1) != 1:
            await self._reject(conn, "unsupported_version", req_id)
            return
        peer = msg.get("peer") or ""
        if not valid_phone_peer(peer):
            await self._reject(conn, "rejected", req_id)
            return

        async with self._hello_lock:
            if self.has_live_session():
                logger.info("live preempt denied")
                await self._reject(conn, "rejected", req_id)
                return
            self._outbound_ready.clear()
            if conn.accepted_code:
                ok, err = self._state.try_consume_pair(conn.accepted_code, peer)
                if not ok:
                    await self._reject(conn, err or "rejected", req_id)
                    return
                self._promote(conn, peer)
                logger.info(
                    f"hello accepted_code peer={peer[:12]} "
                    f"session={self._state.get_session_id()}"
                )
            else:
                ok, err = self._state.check_reconnect(peer)
                if not ok:
                    await self._reject(conn, err or "unpaired", req_id)
                    return
                self._promote(conn, peer)
                logger.info(
                    f"hello reconnect peer={peer[:12]} "
                    f"session={self._state.get_session_id()}"
                )
            ev = self._outbound_ready
            gen = self._outbound_gen

        sid = self._state.get_session_id() or self._state.rotate_session()
        seq = self._state.get_reply_seq()
        hello_ok = {
            "v": 1,
            "ok": True,
            "id": req_id,
            "op": "hello",
            "sessionId": sid,
            "seq": seq,
        }
        try:
            last_seen = msg.get("lastSeenSeq")
            phone_sid = msg.get("sessionId")
            snapshot: list[dict] = []
            if (
                isinstance(last_seen, int)
                and not isinstance(last_seen, bool)
                and (not isinstance(phone_sid, str) or phone_sid == (sid or ""))
            ):
                snapshot = self._log.after(last_seen)
            await conn.ws.send_json(hello_ok)
            for stored in snapshot:
                await conn.ws.send_json(
                    {**stored, "v": 1, "op": "reply", "live": False, "catchUp": True}
                )
            if snapshot:
                logger.info(
                    f"catchup n={len(snapshot)} after={last_seen} session={sid}"
                )
        finally:
            if self._live is conn and self._outbound_gen == gen:
                ev.set()

    async def _reject(
        self,
        conn: SessionConn,
        error: str,
        req_id: str | None = None,
    ) -> None:
        payload = error_payload(error, req_id)
        try:
            await conn.ws.send_json(payload)
        except Exception:
            pass
        try:
            await conn.ws.close()
        except Exception:
            pass
