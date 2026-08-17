"""Grok Bot desktop roster via the host-local gateway."""

from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

import aiohttp

from agent import AgentInfo, AgentUnavailable

logger = logging.getLogger(__name__)

LIST_TIMEOUT_SEC = 8.0
_LIST_KEYS = ("agents", "items", "data", "results", "rows")
_ROSTER_SUFFIX = ".roster.last-roster"
_LAST_AGENT_SUFFIX = ".selection.last-agent"


@dataclass
class GrokBotRoster:
    agents: list[AgentInfo]
    stale: bool
    source: str
    last_agent_id: str | None


def decode_blob_stem(stem: str) -> str | None:
    """RFC 4648 base32 of the key. The `.blob` suffix is not part of the key."""
    pad = "=" * ((8 - len(stem) % 8) % 8)
    try:
        return base64.b32decode(stem.upper() + pad, casefold=True).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError, ValueError):
        return None


def normalize_agents(data: object) -> list[AgentInfo]:
    items: list = []
    if isinstance(data, list):
        items = data
    elif isinstance(data, dict):
        for key in _LIST_KEYS:
            val = data.get(key)
            if isinstance(val, list):
                items = val
                break
    out: list[AgentInfo] = []
    seen: set[str] = set()
    for item in items:
        info = _normalize_agent(item)
        if info is None or info.id in seen:
            continue
        seen.add(info.id)
        out.append(info)
    return out


def read_last_roster(persistence_dir: str | None) -> list[AgentInfo]:
    blob = _newest_blob(persistence_dir, _ROSTER_SUFFIX)
    if blob is None:
        return []
    value = _read_blob_value(blob)
    return normalize_agents(_last_roster_items(value))


def read_last_agent(persistence_dir: str | None) -> str | None:
    blob = _newest_blob(persistence_dir, _LAST_AGENT_SUFFIX)
    if blob is None:
        return None
    value = _read_blob_value(blob)
    if not isinstance(value, dict):
        return None
    aid = value.get("agentId")
    if isinstance(aid, str) and aid.strip():
        return aid.strip()
    return None


class GrokBotBackend:
    name = "grokbot"

    def __init__(
        self,
        *,
        gateway_path: str,
        persistence_dir: str | None,
        timeout_sec: float = 120.0,
        poll_sec: float = 0.5,
        tail_limit: int = 50,
        tail_pages: int = 4,
    ):
        self._gateway_path = gateway_path
        self._persistence_dir = persistence_dir
        self._timeout_sec = timeout_sec
        self._poll_sec = poll_sec
        self._tail_limit = tail_limit
        self._tail_pages = tail_pages
        self._abort = asyncio.Event()
        self._live_cache: list[AgentInfo] = []
        self._last_complete: AgentInfo | None = None

    async def list_agents(self) -> list[AgentInfo]:
        roster = await self.list_roster()
        return list(roster.agents)

    async def list_roster(self) -> GrokBotRoster:
        self._abort.clear()
        try:
            data = await self._post("listAgents", {})
            agents = normalize_agents(data)
            self._live_cache = list(agents)
            last_agent_id = _last_agent_if_member(self._persistence_dir, agents)
            logger.info(f"grokbot listAgents n={len(agents)} stale=0 source=live")
            return GrokBotRoster(
                agents=agents,
                stale=False,
                source="live",
                last_agent_id=last_agent_id,
            )
        except AgentUnavailable as e:
            if e.detail in {"cancelled", "not_implemented"}:
                raise
            agents = read_last_roster(self._persistence_dir)
            if not agents:
                raise AgentUnavailable(detail="no_gateway") from e
            last_agent_id = _last_agent_if_member(self._persistence_dir, agents)
            logger.info(
                f"grokbot listAgents n={len(agents)} stale=1 source=last-roster"
            )
            return GrokBotRoster(
                agents=agents,
                stale=True,
                source="last-roster",
                last_agent_id=last_agent_id,
            )

    async def complete(
        self, *, text: str, agent_id: str | None, phone_peer: str
    ) -> str:
        parts: list[str] = []
        async for bubble in self.stream_complete(
            text=text, agent_id=agent_id, phone_peer=phone_peer
        ):
            if bubble:
                parts.append(bubble)
        return "\n\n".join(parts)

    async def stream_complete(
        self, *, text: str, agent_id: str | None, phone_peer: str
    ):
        self._abort.clear()
        try:
            resolved, nonce, deadline = await self._begin_turn(
                text=text, agent_id=agent_id
            )
            async for bubble in self._iter_turn(resolved, nonce, deadline):
                yield bubble
        except asyncio.CancelledError:
            raise AgentUnavailable(detail="cancelled") from None
        except AgentUnavailable as e:
            if e.detail != "cancelled":
                logger.info(f"grokbot error detail={e.detail}")
            raise

    async def name_for(self, agent_id: str | None) -> AgentInfo | None:
        if self._last_complete is not None and (
            not agent_id or self._last_complete.id == agent_id
        ):
            return self._last_complete
        if not agent_id:
            return None
        for info in self._live_cache:
            if info.id == agent_id:
                return info
        return None

    async def cancel(self) -> None:
        self._abort.set()

    async def _begin_turn(
        self, *, text: str, agent_id: str | None
    ) -> tuple[str, str, float]:
        # Membership is live-only; last-roster is not a send fallback.
        try:
            data = await self._post("listAgents", {})
        except AgentUnavailable as e:
            if e.detail == "cancelled":
                raise
            raise AgentUnavailable(detail="no_gateway") from e
        agents = normalize_agents(data)
        self._live_cache = list(agents)

        resolved = _resolve_send_agent(agent_id, agents, self._persistence_dir)
        if resolved is None:
            raise AgentUnavailable(detail="no_agent")
        by_id = {info.id: info for info in agents}
        if resolved not in by_id:
            raise AgentUnavailable(detail="unknown_agent")
        self._last_complete = by_id[resolved]

        nonce = str(uuid4())
        deadline = time.monotonic() + self._timeout_sec
        result = await self._post(
            "sendPrompt",
            {"agentId": resolved, "prompt": text, "clientNonce": nonce},
            timeout_sec=_remaining(deadline),
        )
        if (
            isinstance(result, dict)
            and "accepted" in result
            and result["accepted"] is not True
        ):
            raise AgentUnavailable(detail="rejected")
        logger.info("grokbot sendPrompt accepted=1")
        return resolved, nonce, deadline

    async def _iter_turn(self, agent_id: str, nonce: str, deadline: float):
        poll_sec = self._poll_sec if self._poll_sec > 0 else 0.5
        started = time.monotonic()
        emitted: set[str] = set()
        echo_seen = False
        yielded_any = False
        reason = "timeout"
        try:
            while True:
                if self._abort.is_set():
                    raise AgentUnavailable(detail="cancelled")
                if _remaining(deadline) <= 0:
                    break
                try:
                    entries, echo_idx = await self._fetch_assembled(
                        agent_id, nonce, deadline
                    )
                except AgentUnavailable as e:
                    if e.detail == "cancelled":
                        raise
                    if e.detail == "timeout" or _remaining(deadline) <= 0:
                        break
                    raise
                if echo_idx is None:
                    sleep_for = min(poll_sec, _remaining(deadline))
                    if sleep_for <= 0:
                        break
                    await self._sleep_or_abort(sleep_for)
                    continue
                echo_seen = True
                slice_rows, cut = _this_turn_slice(entries, echo_idx, nonce)
                for key, content, streaming in _iter_sm_text(slice_rows):
                    out = _maybe_yield_sm(emitted, cut, key, content, streaming)
                    if out is None:
                        continue
                    yielded_any = True
                    logger.info(
                        f"grokbot bubble chars={len(out)} emitted={len(emitted)}"
                    )
                    yield out
                if cut and yielded_any:
                    reason = "cut"
                    return
                sleep_for = min(poll_sec, _remaining(deadline))
                if sleep_for <= 0:
                    break
                await self._sleep_or_abort(sleep_for)

            if self._abort.is_set():
                raise AgentUnavailable(detail="cancelled")
            if echo_seen and not yielded_any:
                yield ""
                return
            if not echo_seen:
                raise AgentUnavailable(detail="timeout")
        except asyncio.CancelledError:
            reason = "cancel"
            raise
        except AgentUnavailable as e:
            if e.detail == "cancelled":
                reason = "cancel"
            elif e.detail != "timeout":
                reason = e.detail
            raise
        finally:
            _log_watch_end(started, reason, len(emitted))

    async def _fetch_assembled(
        self, agent_id: str, nonce: str, deadline: float
    ) -> tuple[list, int | None]:
        remaining = _remaining(deadline)
        page = await self._post(
            "getAgentTranscriptTail",
            {"id": agent_id, "limit": self._tail_limit},
            timeout_sec=remaining,
        )
        entries = _tail_entries(page)
        echo_idx = _find_nonce_echo(entries, nonce)
        if echo_idx is not None:
            return entries, echo_idx

        next_before = _next_before_seq(page)
        walked = 0
        while (
            echo_idx is None
            and isinstance(next_before, int)
            and walked < self._tail_pages
        ):
            remaining = _remaining(deadline)
            if remaining <= 0:
                break
            older_page = await self._post(
                "getAgentTranscriptTail",
                {
                    "id": agent_id,
                    "limit": self._tail_limit,
                    "beforeSeq": next_before,
                },
                timeout_sec=remaining,
            )
            entries = _tail_entries(older_page) + entries
            echo_idx = _find_nonce_echo(entries, nonce)
            next_before = _next_before_seq(older_page)
            walked += 1
        return entries, echo_idx

    async def _sleep_or_abort(self, seconds: float) -> None:
        try:
            await asyncio.wait_for(self._abort.wait(), timeout=seconds)
        except asyncio.TimeoutError:
            return
        raise AgentUnavailable(detail="cancelled")

    def _load_conn(self) -> dict:
        path = Path(self._gateway_path).expanduser()
        try:
            conn = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError, UnicodeError):
            raise AgentUnavailable(detail="no_gateway") from None
        if not isinstance(conn, dict):
            raise AgentUnavailable(detail="no_gateway")
        base = conn.get("baseUrl")
        token = conn.get("token")
        if (
            not isinstance(base, str)
            or not base.strip()
            or not isinstance(token, str)
            or not token.strip()
        ):
            raise AgentUnavailable(detail="no_gateway")
        headers = {
            "Authorization": f"Bearer {token.strip()}",
            "Content-Type": "application/json",
        }
        extra = conn.get("headers")
        if isinstance(extra, dict):
            merged: dict[str, str] = {}
            for key, value in extra.items():
                if (
                    isinstance(key, str)
                    and isinstance(value, str)
                    and key.strip()
                    and value
                ):
                    merged[key] = value
            merged.update(headers)
            headers = merged
        return {"baseUrl": base.strip(), "headers": headers}

    async def _post(
        self, method: str, body: object, *, timeout_sec: float | None = None
    ) -> object:
        # Re-read each call: the connection file URL rotates with the pod.
        conn = self._load_conn()
        url = f"{conn['baseUrl'].rstrip('/')}/api/{method}"
        headers = conn["headers"]
        if timeout_sec is None:
            total = LIST_TIMEOUT_SEC if method == "listAgents" else self._timeout_sec
        else:
            total = timeout_sec
        if total <= 0:
            raise AgentUnavailable(detail="timeout")
        timeout = aiohttp.ClientTimeout(total=total)
        async with aiohttp.ClientSession(timeout=timeout) as sess:
            return await self._request_with_abort(sess, method, url, headers, body)

    async def _request_with_abort(
        self,
        sess: aiohttp.ClientSession,
        method: str,
        url: str,
        headers: dict[str, str],
        body: object,
    ):
        abort_task = asyncio.create_task(self._abort.wait())
        req_task = asyncio.create_task(self._do_post(sess, method, url, headers, body))
        try:
            done, pending = await asyncio.wait(
                {abort_task, req_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            if abort_task in done and self._abort.is_set() and not req_task.done():
                raise AgentUnavailable(detail="cancelled")
            if req_task in done:
                return req_task.result()
            raise AgentUnavailable(detail="cancelled")
        finally:
            for task in (abort_task, req_task):
                if not task.done():
                    task.cancel()

    async def _do_post(
        self,
        sess: aiohttp.ClientSession,
        method: str,
        url: str,
        headers: dict[str, str],
        body: object,
    ):
        try:
            async with sess.post(url, json=body, headers=headers) as resp:
                logger.info(f"grokbot {method} status={resp.status}")
                if resp.status in (401, 403):
                    raise AgentUnavailable(detail="no_gateway")
                if resp.status >= 400:
                    raise AgentUnavailable(detail="gateway_error")
                try:
                    return await resp.json(content_type=None)
                except (aiohttp.ContentTypeError, json.JSONDecodeError, ValueError):
                    raise AgentUnavailable(detail="gateway_error") from None
        except AgentUnavailable:
            raise
        except asyncio.CancelledError:
            raise
        except Exception:
            raise AgentUnavailable(detail="gateway_error") from None


def _normalize_agent(item: object) -> AgentInfo | None:
    if not isinstance(item, dict):
        return None
    if item.get("isGroup") is True or item.get("isHiddenFromSidebar") is True:
        return None
    aid = item.get("id")
    if not isinstance(aid, str) or not aid.strip():
        aid = item.get("agentId")
    if not isinstance(aid, str) or not aid.strip():
        return None
    name = item.get("name")
    if not isinstance(name, str) or not name.strip():
        name = item.get("title")
    if not isinstance(name, str):
        name = ""
    return AgentInfo(id=aid.strip(), name=name.strip())


def _last_roster_items(value: object) -> object:
    if not isinstance(value, dict):
        return []
    rows = value.get("rows")
    if isinstance(rows, list):
        return rows
    agents = value.get("agents")
    if isinstance(agents, list):
        return agents
    if isinstance(agents, dict):
        items = []
        for key, item in agents.items():
            if isinstance(item, dict):
                if not item.get("id") and not item.get("agentId"):
                    item = {**item, "id": str(key)}
                items.append(item)
            elif isinstance(item, str) and item.strip():
                items.append({"id": str(key), "name": item})
        return items
    return []


def _read_blob_value(path: Path) -> object:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeError):
        return None
    if not isinstance(data, dict):
        return None
    return data.get("value")


def _newest_blob(persistence_dir: str | None, suffix: str) -> Path | None:
    if not persistence_dir:
        return None
    root = Path(persistence_dir).expanduser()
    if not root.is_dir():
        return None
    best_path: Path | None = None
    best_mtime: float | None = None
    for path in root.glob("*.blob"):
        key = decode_blob_stem(path.stem)
        if key is None or not key.endswith(suffix):
            continue
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if best_mtime is None or mtime > best_mtime:
            best_path = path
            best_mtime = mtime
    return best_path


def _last_agent_if_member(
    persistence_dir: str | None, agents: list[AgentInfo]
) -> str | None:
    aid = read_last_agent(persistence_dir)
    if aid and any(agent.id == aid for agent in agents):
        return aid
    return None


def _resolve_send_agent(
    agent_id: str | None,
    agents: list[AgentInfo],
    persistence_dir: str | None,
) -> str | None:
    if isinstance(agent_id, str) and agent_id.strip():
        return agent_id.strip()
    last = read_last_agent(persistence_dir)
    if last and any(agent.id == last for agent in agents):
        return last
    if agents:
        return agents[0].id
    return None


def _remaining(deadline: float) -> float:
    return max(0.0, deadline - time.monotonic())


def _log_watch_end(started: float, reason: str, emitted: int) -> None:
    ms = int((time.monotonic() - started) * 1000)
    logger.info(f"grokbot watch end reason={reason} emitted={emitted} ms={ms}")


def _tail_entries(page: object) -> list:
    if isinstance(page, dict):
        entries = page.get("entries")
        if isinstance(entries, list):
            return list(entries)
    if isinstance(page, list):
        return list(page)
    return []


def _next_before_seq(page: object) -> object:
    if isinstance(page, dict):
        return page.get("nextBeforeSeq")
    return None


def _is_nonce_user(row: object, nonce: str) -> bool:
    if not isinstance(row, dict):
        return False
    if row.get("kind") != "message" or row.get("role") != "user":
        return False
    return row.get("clientNonce") == nonce or row.get("id") == nonce


def _is_other_user(row: object, nonce: str) -> bool:
    if not isinstance(row, dict):
        return False
    if row.get("kind") != "message" or row.get("role") != "user":
        return False
    return not (row.get("clientNonce") == nonce or row.get("id") == nonce)


def _find_nonce_echo(entries: list, nonce: str) -> int | None:
    found: int | None = None
    for i, row in enumerate(entries):
        if _is_nonce_user(row, nonce):
            found = i
    return found


def _this_turn_slice(entries: list, echo_idx: int, nonce: str) -> tuple[list, bool]:
    slice_rows: list = []
    cut = False
    for row in entries[echo_idx + 1 :]:
        if _is_other_user(row, nonce):
            cut = True
            break
        slice_rows.append(row)
    return slice_rows, cut


def _sm_text_content(row: object) -> str | None:
    if not isinstance(row, dict) or row.get("kind") != "send-message":
        return None
    message = row.get("message")
    if not isinstance(message, dict) or message.get("type") != "text":
        return None
    content = message.get("content")
    if not isinstance(content, str):
        return None
    stripped = content.strip()
    return stripped or None


def _row_streaming(row: dict) -> bool:
    return row.get("streaming") is True or row.get("isStreaming") is True


def _sm_row_key(row: dict, ordinal: int, content: str) -> str:
    rid = row.get("id")
    if isinstance(rid, str) and rid.strip():
        return rid.strip()
    seq = row.get("seq")
    if isinstance(seq, int):
        return f"seq:{seq}"
    entry_seq = row.get("entrySeq")
    if isinstance(entry_seq, int):
        return f"entrySeq:{entry_seq}"
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()[:12]
    return f"sm-{ordinal}-{digest}"


def _iter_sm_text(rows: list):
    ordinal = 0
    for row in rows:
        content = _sm_text_content(row)
        if content is None or not isinstance(row, dict):
            continue
        yield _sm_row_key(row, ordinal, content), content, _row_streaming(row)
        ordinal += 1


def _maybe_yield_sm(
    emitted: set[str], cut: bool, key: str, content: str, streaming: bool
) -> str | None:
    # Cut treats a non-empty in-flight row as finalized.
    if key in emitted:
        return None
    if streaming and not cut:
        return None
    if not content:
        return None
    emitted.add(key)
    return content
