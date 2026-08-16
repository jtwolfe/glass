"""WebRTC DataChannel peer for glass-pair v1 (answerer side)."""

import asyncio
import logging
from typing import Optional, Callable, Awaitable

from aiortc import (
    RTCPeerConnection,
    RTCSessionDescription,
    RTCConfiguration,
    RTCIceServer,
)
from aiortc.rtcdatachannel import RTCDataChannel

from ntfy_signaling import NtfySignaling, SignalingMessage, SignalingType
from protocol import ProtocolHandler, ProtocolConfig, Agent

logger = logging.getLogger(__name__)


class WebRtcPeer:
    """
    WebRTC DataChannel peer (answerer side) for glass-pair v1.

    Flow:
    1. Subscribe to ntfy topic
    2. Wait for offer from phone
    3. Create answer, publish via ntfy
    4. Exchange ICE candidates via ntfy
    5. DataChannel opens → ntfy signaling done
    6. Handle DataChannel messages via ProtocolHandler

    STUN only, no TURN. Fail closed if connection cannot be established.
    """

    def __init__(
        self,
        signaling: NtfySignaling,
        stun_server: str,
        protocol_config: ProtocolConfig,
        on_hello: Optional[Callable[[str], Awaitable[None]]] = None,
        on_disconnect: Optional[Callable[[], Awaitable[None]]] = None,
    ):
        """
        Initialize WebRTC peer.

        Args:
            signaling: ntfy signaling client
            stun_server: STUN server URL (e.g. "stun:stun.l.google.com:19302")
            protocol_config: DataChannel protocol configuration
            on_hello: Callback when hello received
            on_disconnect: Callback when connection closed
        """
        self._signaling = signaling
        self._stun_server = stun_server
        self._protocol = ProtocolHandler(protocol_config, on_hello=on_hello)
        self._on_disconnect = on_disconnect

        self._pc: Optional[RTCPeerConnection] = None
        self._channel: Optional[RTCDataChannel] = None
        self._connected = False
        self._running = False
        self._subscribe_task: Optional[asyncio.Task] = None

    @property
    def is_connected(self) -> bool:
        """Check if DataChannel is open."""
        return self._connected

    @property
    def phone_peer(self) -> Optional[str]:
        """Get phone peer ID from hello message."""
        return self._protocol.phone_peer

    def set_agents(self, agents: list[Agent]) -> None:
        """Update available agents."""
        self._protocol.set_agents(agents)

    async def start(self) -> None:
        """Start the peer and begin listening for offers."""
        if self._running:
            return

        self._running = True
        await self._signaling.start()

        # Start subscribe task
        self._subscribe_task = asyncio.create_task(
            self._signaling.subscribe(self._on_signaling_message)
        )
        logger.info("WebRTC peer started, waiting for offer...")

    async def stop(self) -> None:
        """Stop the peer and close connections."""
        self._running = False

        if self._subscribe_task and not self._subscribe_task.done():
            self._subscribe_task.cancel()
            try:
                await self._subscribe_task
            except asyncio.CancelledError:
                pass

        await self._close_connection()
        await self._signaling.stop()
        logger.info("WebRTC peer stopped")

    async def _close_connection(self) -> None:
        """Close the peer connection."""
        if self._channel:
            self._channel.close()
            self._channel = None

        if self._pc:
            await self._pc.close()
            self._pc = None

        was_connected = self._connected
        self._connected = False

        if was_connected and self._on_disconnect:
            try:
                await self._on_disconnect()
            except Exception as e:
                logger.error(f"Disconnect callback error: {e}")

    async def _on_signaling_message(self, msg: SignalingMessage) -> None:
        """Handle incoming signaling message from ntfy."""
        if msg.type == SignalingType.OFFER:
            await self._handle_offer(msg.payload)
        elif msg.type == SignalingType.ICE:
            await self._handle_ice(msg.payload)
        # Ignore ANSWER (we're the answerer)

    async def _handle_offer(self, sdp: str) -> None:
        """Handle incoming WebRTC offer from phone."""
        logger.info("Received WebRTC offer")

        # Close any existing connection
        await self._close_connection()

        # Create new peer connection
        config = RTCConfiguration(
            iceServers=[RTCIceServer(urls=[self._stun_server])]
        )
        self._pc = RTCPeerConnection(config)

        # Set up event handlers
        self._pc.on("datachannel", self._on_datachannel)
        self._pc.on("icecandidate", self._on_ice_candidate)
        self._pc.on("connectionstatechange", self._on_connection_state_change)

        # Set remote description (offer)
        offer = RTCSessionDescription(sdp=sdp, type="offer")
        await self._pc.setRemoteDescription(offer)

        # Create and set local description (answer)
        answer = await self._pc.createAnswer()
        await self._pc.setLocalDescription(answer)

        # Publish answer via ntfy
        success = await self._signaling.publish_answer(answer.sdp)
        if success:
            logger.info("Published WebRTC answer")
        else:
            logger.warning("Failed to publish WebRTC answer")

    async def _handle_ice(self, candidate_str: str) -> None:
        """Handle incoming ICE candidate from phone."""
        if not self._pc:
            return

        # aiortc expects the full candidate line
        # The Android side sends just the candidate string
        try:
            from aiortc import RTCIceCandidate

            # Parse candidate string (e.g. "candidate:... typ host ...")
            # aiortc can handle the raw SDP format
            if candidate_str.startswith("candidate:"):
                # Add ICE candidate
                candidate = RTCIceCandidate(
                    component=1,
                    foundation="",
                    ip="",
                    port=0,
                    priority=0,
                    protocol="",
                    type="",
                    sdpMid="0",
                    sdpMLineIndex=0,
                )
                # Let aiortc parse from SDP
                await self._pc.addIceCandidate(candidate_str)
        except Exception as e:
            logger.debug(f"ICE candidate parse error (often benign): {e}")

    def _on_datachannel(self, channel: RTCDataChannel) -> None:
        """Handle incoming DataChannel."""
        logger.info(f"DataChannel opened: {channel.label}")
        self._channel = channel
        self._connected = True

        @channel.on("message")
        async def on_message(message):
            await self._on_channel_message(message)

        @channel.on("close")
        def on_close():
            logger.info("DataChannel closed")
            self._connected = False
            if self._on_disconnect:
                asyncio.create_task(self._on_disconnect())

    async def _on_channel_message(self, message) -> None:
        """Handle DataChannel message."""
        if isinstance(message, bytes):
            data = message.decode("utf-8")
        else:
            data = message

        logger.debug(f"DataChannel message: {data[:100]}...")

        response = await self._protocol.handle_message(data)
        if response and self._channel and self._channel.readyState == "open":
            self._channel.send(response)

    async def _on_ice_candidate(self, candidate) -> None:
        """Handle local ICE candidate."""
        if candidate and candidate.candidate:
            await self._signaling.publish_ice(candidate.candidate)

    async def _on_connection_state_change(self) -> None:
        """Handle connection state changes."""
        if not self._pc:
            return

        state = self._pc.connectionState
        logger.info(f"Connection state: {state}")

        if state in ("failed", "closed", "disconnected"):
            await self._close_connection()

    async def send_message(self, text: str, from_agent: str = "Assistant") -> bool:
        """
        Send a message to the phone.

        Args:
            text: Message text
            from_agent: Sender name

        Returns:
            True if sent successfully
        """
        if not self._channel or self._channel.readyState != "open":
            return False

        message = self._protocol.add_reply(from_agent, text)

        # Send as JSON
        import json
        payload = json.dumps({
            "v": 1,
            "ok": True,
            "id": message.id,
            "from": message.from_,
            "text": message.text,
            "at": message.at,
        })

        self._channel.send(payload)
        return True
