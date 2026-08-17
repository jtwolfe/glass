"""Agent backends for glass-peer (Echo, HTTP, ACP, GrokBot)."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import aiohttp

from acp_client import AcpClient, AcpError

AGENT_UNAVAILABLE_DETAILS = frozenset(
    {
        "unknown_agent",
        "no_agent",
        "no_gateway",
        "rejected",
        "timeout",
        "cancelled",
        "not_implemented",
        "gateway_error",
    }
)


class AgentUnavailable(Exception):
    """Hard agent failure (HTTP 503, timeout, cancelled)."""

    def __init__(self, detail: str = "gateway_error") -> None:
        if detail in AGENT_UNAVAILABLE_DETAILS:
            self.detail = detail
        else:
            # Keep historical messages for ACP/HTTP; never put them on the wire.
            self.detail = "gateway_error"
        super().__init__(detail)


@dataclass(frozen=True)
class AgentInfo:
    id: str
    name: str


class AgentBackend(Protocol):
    name: str

    async def list_agents(self) -> list[AgentInfo]: ...

    async def complete(
        self, *, text: str, agent_id: str | None, phone_peer: str
    ) -> str:
        """Return assistant text. Raise AgentUnavailable on hard failure."""
        ...

    async def cancel(self) -> None:
        """Abort in-flight complete (kick / remint)."""
        ...


class EchoBackend:
    """Default stub: prefix the user text."""

    name = "echo"

    async def list_agents(self) -> list[AgentInfo]:
        return [AgentInfo(id="echo", name="Echo")]

    async def complete(
        self, *, text: str, agent_id: str | None, phone_peer: str
    ) -> str:
        return "echo: " + text

    async def cancel(self) -> None:
        return None


class HttpBackend:
    """Localhost HTTP JSON agent (GET /agents, POST /complete)."""

    name = "http"

    def __init__(self, base_url: str, timeout_sec: float = 120.0):
        self._base = base_url.rstrip("/")
        self._timeout = aiohttp.ClientTimeout(total=timeout_sec)
        self._session: aiohttp.ClientSession | None = None
        self._abort = asyncio.Event()

    async def _client(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(timeout=self._timeout)
        return self._session

    async def _request(self, method: str, path: str, **kwargs):
        sess = await self._client()
        abort_task = asyncio.create_task(self._abort.wait())
        req_task = asyncio.create_task(self._do_request(sess, method, path, kwargs))
        try:
            done, pending = await asyncio.wait(
                {abort_task, req_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            if abort_task in done and self._abort.is_set() and not req_task.done():
                raise AgentUnavailable("cancelled")
            if req_task in done:
                return req_task.result()
            raise AgentUnavailable("cancelled")
        finally:
            for task in (abort_task, req_task):
                if not task.done():
                    task.cancel()

    async def _do_request(
        self, sess: aiohttp.ClientSession, method: str, path: str, kwargs: dict
    ):
        async with sess.request(method, f"{self._base}{path}", **kwargs) as resp:
            if resp.status == 503:
                raise AgentUnavailable("unavailable")
            resp.raise_for_status()
            return await resp.json()

    async def list_agents(self) -> list[AgentInfo]:
        self._abort.clear()
        try:
            data = await self._request("GET", "/agents")
        except AgentUnavailable:
            raise
        except Exception as e:
            raise AgentUnavailable(str(e)) from e
        agents = []
        for item in data.get("agents") or []:
            agents.append(AgentInfo(id=item["id"], name=item["name"]))
        return agents

    async def complete(
        self, *, text: str, agent_id: str | None, phone_peer: str
    ) -> str:
        self._abort.clear()
        payload = {"text": text, "agentId": agent_id, "phonePeer": phone_peer}
        try:
            data = await self._request("POST", "/complete", json=payload)
            return str(data.get("text", ""))
        except asyncio.CancelledError:
            raise
        except AgentUnavailable:
            raise
        except Exception as e:
            raise AgentUnavailable(str(e)) from e

    async def cancel(self) -> None:
        self._abort.set()


class AcpBackend:
    """ACP v1 stdio child (GLASS_AGENT_ACP_CMD)."""

    name = "acp"

    def __init__(
        self,
        cmd: str,
        *,
        cwd: str | None = None,
        display_name: str = "Grok",
        yolo: bool = True,
        timeout_sec: float = 120.0,
    ):
        self._display_name = display_name
        self._client = AcpClient(cmd, cwd=cwd, yolo=yolo, timeout_sec=timeout_sec)

    async def list_agents(self) -> list[AgentInfo]:
        return [AgentInfo(id="acp", name=self._display_name)]

    async def complete(
        self, *, text: str, agent_id: str | None, phone_peer: str
    ) -> str:
        try:
            return await self._client.prompt(text)
        except asyncio.CancelledError:
            raise
        except AcpError as e:
            raise AgentUnavailable(str(e)) from e
        except Exception as e:
            raise AgentUnavailable(str(e)) from e

    async def cancel(self) -> None:
        await self._client.cancel_and_kill()


def select_backend(
    *,
    kind: str,
    url: str | None,
    timeout_sec: float,
    acp_cmd: str | None = None,
    acp_cwd: str | None = None,
    acp_name: str = "Grok",
    acp_yolo: bool = True,
    grokbot_gateway_path: str | None = None,
    grokbot_persistence_dir: str | None = None,
    grokbot_poll_sec: float = 0.5,
    grokbot_tail_limit: int = 50,
    grokbot_tail_pages: int = 4,
) -> AgentBackend:
    """Construct the configured backend."""
    if kind == "echo":
        return EchoBackend()
    if kind == "http":
        if not url:
            raise ValueError("GLASS_AGENT_URL is required for the http backend")
        return HttpBackend(url, timeout_sec)
    if kind == "acp":
        if not acp_cmd:
            raise ValueError("GLASS_AGENT_ACP_CMD is required for the acp backend")
        return AcpBackend(
            acp_cmd,
            cwd=acp_cwd,
            display_name=acp_name,
            yolo=acp_yolo,
            timeout_sec=timeout_sec,
        )
    if kind == "grokbot":
        from grokbot import GrokBotBackend

        gateway = grokbot_gateway_path or str(
            Path.home() / ".grokbot" / "local-exec-daemon-connection.json"
        )
        persist = grokbot_persistence_dir
        if persist is None:
            persist = str(
                Path.home() / ".config" / "Grok Bot" / "sand-client-persistence"
            )
        return GrokBotBackend(
            gateway_path=gateway,
            persistence_dir=persist,
            timeout_sec=timeout_sec,
            poll_sec=grokbot_poll_sec,
            tail_limit=grokbot_tail_limit,
            tail_pages=grokbot_tail_pages,
        )
    raise ValueError(f"unknown GLASS_AGENT_BACKEND={kind}")
