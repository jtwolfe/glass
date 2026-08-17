"""WebSocket session protocol (hello-ack, send, agents, live reply)."""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import TYPE_CHECKING

from agent import AgentBackend, AgentInfo, AgentUnavailable
from session_log import SessionLog
from state import StateStore

if TYPE_CHECKING:
    from session import SessionServer

logger = logging.getLogger(__name__)

_WIRE_ERROR_DETAILS = frozenset(
    {
        "unknown_agent",
        "no_agent",
        "no_gateway",
        "rejected",
        "timeout",
        "not_implemented",
        "gateway_error",
    }
)


def error_payload(
    error: str, req_id: str | None = None, detail: str | None = None
) -> dict:
    """Stable-code error object."""
    payload: dict = {"v": 1, "ok": False, "error": error}
    if req_id:
        payload["id"] = req_id
    if detail:
        payload["detail"] = detail
    return payload


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


class ProtocolHandler:
    """Codec + agent trigger."""

    def __init__(
        self,
        state: StateStore,
        backend: AgentBackend,
        log: SessionLog | None = None,
    ):
        self._state = state
        self._backend = backend
        self._log = log if log is not None else SessionLog(state)
        self._state.bind_session_log(self._log.clear)
        self._session: SessionServer | None = None
        self._agent_lock = asyncio.Lock()
        self._agent_tasks: set[asyncio.Task] = set()
        self._emitted_echo_ids: set[str] = set()

    def bind_session(self, session: SessionServer) -> None:
        self._session = session

    async def handle_raw(self, data: str, phone_peer: str) -> dict | None:
        try:
            msg = json.loads(data)
        except json.JSONDecodeError:
            return error_payload("invalid_json")
        if not isinstance(msg, dict):
            return error_payload("rejected")
        return await self.handle(msg, phone_peer)

    async def handle(self, msg: dict, phone_peer: str) -> dict | None:
        req_id = msg.get("id")
        if msg.get("v", 1) != 1:
            return error_payload("unsupported_version", req_id)

        op = msg.get("op", "")
        if op == "send":
            return await self._handle_send(msg, phone_peer)
        if op == "agents":
            return await self._handle_agents(msg)
        if op == "ping":
            return {"v": 1, "ok": True, "id": req_id, "op": "pong"}
        if op == "replies":
            return error_payload("replies_removed", req_id)
        if op == "hello":
            return error_payload("rejected", req_id)
        return error_payload("unknown_op", req_id)

    async def _handle_send(self, msg: dict, phone_peer: str) -> dict:
        req_id = msg.get("id")
        from_user = msg.get("from", "")
        text = msg.get("text", "")
        if not from_user or not text:
            return error_payload("rejected", req_id)

        echo_id = str(uuid.uuid4())
        at = msg.get("at") or _now_iso()
        agent_id = msg.get("agentId")
        logger.info(f"send id={req_id} agent={agent_id}")

        task = asyncio.create_task(
            self._complete_and_reply(
                text=text,
                agent_id=agent_id,
                phone_peer=phone_peer,
                echo_id=echo_id,
            )
        )
        self._agent_tasks.add(task)
        task.add_done_callback(self._agent_tasks.discard)
        return {
            "v": 1,
            "ok": True,
            "id": req_id,
            "echoId": echo_id,
            "from": from_user,
            "text": text,
            "at": at,
        }

    async def _complete_and_reply(
        self,
        *,
        text: str,
        agent_id: str | None,
        phone_peer: str,
        echo_id: str,
    ) -> None:
        try:
            async with self._agent_lock:
                stream = getattr(self._backend, "stream_complete", None)
                try:
                    if callable(stream):
                        n = 0
                        async for bubble in stream(
                            text=text, agent_id=agent_id, phone_peer=phone_peer
                        ):
                            n += 1
                            await self._emit_reply(echo_id, agent_id, bubble)
                        if n == 0:
                            await self._emit_reply(echo_id, agent_id, "")
                    else:
                        reply_text = await self._backend.complete(
                            text=text, agent_id=agent_id, phone_peer=phone_peer
                        )
                        await self._emit_reply(echo_id, agent_id, reply_text)
                except asyncio.CancelledError:
                    logger.info("agent cancelled")
                    return
                except AgentUnavailable as e:
                    if e.detail == "cancelled":
                        logger.info("agent cancelled")
                        return
                    logger.info(f"agent error detail={e.detail}")
                    if not self._turn_emitted(echo_id):
                        await self._emit_agent_error(
                            echo_id=echo_id, agent_id=agent_id, detail=e.detail
                        )
                    return
                finally:
                    self._emitted_echo_ids.discard(echo_id)
        except asyncio.CancelledError:
            logger.info("agent cancelled")
            return

    async def _emit_reply(
        self, echo_id: str, agent_id: str | None, reply_text: str
    ) -> None:
        info = await self._pick_agent(agent_id)
        payload = {
            "id": str(uuid.uuid4()),
            "from": info.name,
            "agentId": info.id,
            "text": reply_text if reply_text else "(no text)",
            "at": _now_iso(),
            "inReplyTo": echo_id,
        }
        stored = self._log.allocate_and_append(payload)
        if stored is None:
            logger.info("reply dropped log_full")
            return
        self._mark_turn_emitted(echo_id)
        logger.info(f"reply seq={stored['seq']}")
        frame = {**stored, "v": 1, "op": "reply", "live": True}
        if not self._session:
            logger.info("reply queued no_live seq=%s", stored["seq"])
            return
        sent = await self._session.send_json(frame)
        if not sent:
            logger.info("reply queued no_live seq=%s", stored["seq"])

    def _mark_turn_emitted(self, echo_id: str) -> None:
        self._emitted_echo_ids.add(echo_id)

    def _turn_emitted(self, echo_id: str) -> bool:
        return echo_id in self._emitted_echo_ids

    async def _emit_agent_error(
        self, *, echo_id: str, agent_id: str | None, detail: str
    ) -> None:
        if detail == "cancelled":
            return
        closed = detail if detail in _WIRE_ERROR_DETAILS else "gateway_error"
        if not self._session or not self._session.has_live_session():
            logger.info("reply dropped no_live")
            return
        payload = {
            "v": 1,
            "op": "error",
            "error": "agent_unavailable",
            "id": str(uuid.uuid4()),
            "inReplyTo": echo_id,
            "agentId": agent_id if isinstance(agent_id, str) else "",
            "detail": closed,
        }
        logger.info(f"op=error inReplyTo={echo_id} detail={closed}")
        await self._session.send_json(payload)

    async def _pick_agent(self, agent_id: str | None) -> AgentInfo:
        if hasattr(self._backend, "name_for"):
            info = await self._backend.name_for(agent_id)
            if info is not None:
                return info
            if isinstance(agent_id, str) and agent_id.strip():
                return AgentInfo(id=agent_id, name=agent_id)
            return AgentInfo(id="echo", name="Echo")

        try:
            agents = await self._backend.list_agents()
        except AgentUnavailable:
            if isinstance(agent_id, str) and agent_id.strip():
                return AgentInfo(id=agent_id, name=agent_id)
            return AgentInfo(id="echo", name="Echo")
        if agent_id:
            for agent in agents:
                if agent.id == agent_id:
                    return agent
            if isinstance(agent_id, str) and agent_id.strip():
                return AgentInfo(id=agent_id, name=agent_id)
        if agents:
            return agents[0]
        return AgentInfo(id="echo", name="Echo")

    async def _handle_agents(self, msg: dict) -> dict:
        req_id = msg.get("id")
        list_roster = getattr(self._backend, "list_roster", None)
        if callable(list_roster):
            try:
                roster = await list_roster()
            except AgentUnavailable:
                return error_payload("agent_unavailable", req_id)
            return {
                "v": 1,
                "ok": True,
                "id": req_id,
                "agents": [{"id": a.id, "name": a.name} for a in roster.agents],
                "stale": bool(roster.stale),
                "lastAgentId": roster.last_agent_id,
            }
        try:
            agents = await self._backend.list_agents()
        except AgentUnavailable:
            return error_payload("agent_unavailable", req_id)
        return {
            "v": 1,
            "ok": True,
            "id": req_id,
            "agents": [{"id": a.id, "name": a.name} for a in agents],
        }

    async def cancel_agent(self) -> None:
        tasks = list(self._agent_tasks)
        for task in tasks:
            if not task.done():
                task.cancel()
        for task in tasks:
            try:
                await task
            except (asyncio.CancelledError, AgentUnavailable):
                pass
        await self._backend.cancel()

    async def wait_idle(self) -> None:
        """Wait for in-flight agent work (tests)."""
        for task in list(self._agent_tasks):
            try:
                await task
            except (asyncio.CancelledError, AgentUnavailable):
                pass
