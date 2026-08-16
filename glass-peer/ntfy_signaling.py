"""ntfy signaling client for WebRTC offer/answer/ICE exchange."""

import asyncio
import json
import logging
from dataclasses import dataclass
from enum import Enum
from typing import AsyncIterator, Optional, Callable, Awaitable

import aiohttp

logger = logging.getLogger(__name__)


class SignalingType(Enum):
    OFFER = "offer"
    ANSWER = "answer"
    ICE = "ice"


@dataclass
class SignalingMessage:
    """WebRTC signaling message from ntfy."""

    type: SignalingType
    payload: str  # SDP for offer/answer, candidate string for ICE


class NtfySignaling:
    """
    ntfy signaling for glass-pair v1 WebRTC.

    Protocol:
    - Topic is computed from invite fields
    - Messages are JSON text with format:
      {"v":1,"t":"offer|answer|ice","sdp":"..."}  (offer/answer)
      {"v":1,"t":"ice","cand":"..."}              (ICE candidate)

    Phone is the offerer, peer is the answerer.
    """

    def __init__(self, ntfy_url: str, topic: str):
        """
        Initialize signaling client.

        Args:
            ntfy_url: Base ntfy URL (internal, no path prefix)
            topic: 64-char hex topic hash
        """
        self._ntfy_url = ntfy_url.rstrip("/")
        self._topic = topic
        self._session: Optional[aiohttp.ClientSession] = None
        self._subscribe_task: Optional[asyncio.Task] = None
        self._running = False

    async def start(self) -> None:
        """Start the signaling client."""
        if self._session is None:
            # Long timeout for subscribe, normal for publish
            timeout = aiohttp.ClientTimeout(total=None, sock_read=None)
            self._session = aiohttp.ClientSession(timeout=timeout)
        self._running = True

    async def stop(self) -> None:
        """Stop the signaling client."""
        self._running = False
        if self._subscribe_task and not self._subscribe_task.done():
            self._subscribe_task.cancel()
            try:
                await self._subscribe_task
            except asyncio.CancelledError:
                pass
        if self._session:
            await self._session.close()
            self._session = None

    async def publish_answer(self, sdp: str) -> bool:
        """Publish WebRTC answer SDP to ntfy topic."""
        return await self._publish("answer", sdp=sdp)

    async def publish_ice(self, candidate: str) -> bool:
        """Publish ICE candidate to ntfy topic."""
        if not self._session:
            return False

        payload = json.dumps({"v": 1, "t": "ice", "cand": candidate})

        try:
            async with self._session.post(
                f"{self._ntfy_url}/{self._topic}",
                data=payload,
                headers={"Content-Type": "text/plain"},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                return resp.status == 200
        except Exception as e:
            logger.warning(f"Failed to publish ICE: {e}")
            return False

    async def _publish(self, msg_type: str, sdp: str) -> bool:
        """Publish signaling message."""
        if not self._session:
            return False

        payload = json.dumps({"v": 1, "t": msg_type, "sdp": sdp})

        try:
            async with self._session.post(
                f"{self._ntfy_url}/{self._topic}",
                data=payload,
                headers={"Content-Type": "text/plain"},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                return resp.status == 200
        except Exception as e:
            logger.warning(f"Failed to publish {msg_type}: {e}")
            return False

    async def subscribe(
        self,
        on_message: Callable[[SignalingMessage], Awaitable[None]],
    ) -> None:
        """
        Subscribe to ntfy topic and process signaling messages.

        Uses server-sent events (JSON stream) format.
        Calls on_message for each valid signaling message.
        """
        if not self._session:
            return

        url = f"{self._ntfy_url}/{self._topic}/json"
        logger.info(f"Subscribing to ntfy topic: {self._topic[:16]}...")

        while self._running:
            try:
                async with self._session.get(url) as resp:
                    if resp.status != 200:
                        logger.warning(f"ntfy subscribe failed: {resp.status}")
                        await asyncio.sleep(5)
                        continue

                    async for line in resp.content:
                        if not self._running:
                            break

                        line_str = line.decode("utf-8").strip()
                        if not line_str:
                            continue

                        msg = self._parse_message(line_str)
                        if msg:
                            await on_message(msg)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.warning(f"ntfy subscribe error: {e}")
                if self._running:
                    await asyncio.sleep(5)

    def _parse_message(self, line: str) -> Optional[SignalingMessage]:
        """Parse ntfy NDJSON line into signaling message."""
        try:
            obj = json.loads(line)

            # ntfy wraps messages: {"event":"message","message":"..."}
            event = obj.get("event", "")
            if event != "message":
                return None

            message = obj.get("message", "")
            if not message:
                return None

            inner = json.loads(message)
            version = inner.get("v", -1)
            if version != 1:
                return None

            msg_type = inner.get("t", "")

            if msg_type == "offer":
                sdp = inner.get("sdp", "")
                if sdp:
                    return SignalingMessage(SignalingType.OFFER, sdp)
            elif msg_type == "answer":
                sdp = inner.get("sdp", "")
                if sdp:
                    return SignalingMessage(SignalingType.ANSWER, sdp)
            elif msg_type == "ice":
                cand = inner.get("cand", "")
                if cand:
                    return SignalingMessage(SignalingType.ICE, cand)

            return None

        except (json.JSONDecodeError, KeyError, TypeError):
            return None
