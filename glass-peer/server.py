"""HTTP server for glass-peer with /pair, /qr, and /health endpoints."""

import asyncio
import base64
import io
import json
import logging
import secrets
from typing import Optional

from aiohttp import web
import qrcode
import qrcode.image.svg

from config import Config
from mint import mint_invite, Invite
from state import StateStore
from protocol import ProtocolConfig, Agent
from ntfy_signaling import NtfySignaling
from webrtc_peer import WebRtcPeer

logger = logging.getLogger(__name__)


def check_basic_auth(request: web.Request, username: str, password: str) -> bool:
    """Validate HTTP Basic authentication with constant-time comparison."""
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Basic "):
        return False

    try:
        encoded = auth_header[6:]
        decoded = base64.b64decode(encoded).decode("utf-8")
        req_user, req_pass = decoded.split(":", 1)
        # Use constant-time comparison to prevent timing attacks
        user_ok = secrets.compare_digest(req_user, username)
        pass_ok = secrets.compare_digest(req_pass, password)
        return user_ok and pass_ok
    except (ValueError, UnicodeDecodeError):
        return False


def require_auth(handler):
    """Decorator to require HTTP Basic auth on handler."""
    async def wrapper(request: web.Request) -> web.Response:
        config: Config = request.app["config"]
        if not check_basic_auth(request, config.pair_username, config.pair_password):
            return web.Response(
                status=401,
                headers={"WWW-Authenticate": 'Basic realm="glass-pair"'},
                text="Unauthorized",
            )
        return await handler(request)
    return wrapper


class GlassServer:
    """HTTP server for glass-peer."""

    def __init__(self, config: Config, state_store: StateStore):
        self._config = config
        self._state = state_store
        self._app = web.Application()
        self._peer: Optional[WebRtcPeer] = None
        self._current_invite: Optional[Invite] = None

        # Protocol config (agents can be configured externally)
        self._protocol_config = ProtocolConfig(
            agents=[
                Agent(id="default", name="Assistant"),
            ]
        )

        self._setup_routes()

    def _setup_routes(self) -> None:
        """Set up HTTP routes."""
        self._app.router.add_get("/health", self._health_handler)
        self._app.router.add_get("/pair", self._pair_handler)
        self._app.router.add_post("/pair", self._pair_handler)
        self._app.router.add_get("/qr", self._qr_handler)

        # Store config in app for auth decorator
        self._app["config"] = self._config

    async def _health_handler(self, request: web.Request) -> web.Response:
        """Health check endpoint (unauthenticated)."""
        is_paired = self._state.is_paired()
        is_connected = self._peer.is_connected if self._peer else False

        return web.json_response({
            "status": "ok",
            "paired": is_paired,
            "connected": is_connected,
        })

    @require_auth
    async def _pair_handler(self, request: web.Request) -> web.Response:
        """
        Generate new pairing invite and start WebRTC listener.

        Returns QR JSON for scanning.
        Reminting replaces any existing pair.
        """
        logger.info("Minting new invite (remint clears existing pair)")

        # Mint new invite
        invite = mint_invite(self._config.invite_ttl_seconds)
        self._state.set_invite(invite)
        self._current_invite = invite

        # Stop existing peer if any
        if self._peer:
            await self._peer.stop()

        # Start new WebRTC peer on this invite's topic
        signaling = NtfySignaling(
            self._config.ntfy_internal_url,
            invite.topic,
        )

        async def on_hello(phone_peer: str):
            """Handle hello from phone."""
            self._state.mark_paired(phone_peer)
            logger.info(f"Paired with phone: {phone_peer[:16]}...")

            # Switch to stable topic for reconnects
            stable_topic = self._state.get_stable_topic()
            if stable_topic:
                logger.info(f"Stable topic: {stable_topic[:16]}...")
                # Note: In a full implementation, we'd restart
                # signaling on the stable topic here

        self._peer = WebRtcPeer(
            signaling=signaling,
            stun_server=self._config.stun_server,
            protocol_config=self._protocol_config,
            on_hello=on_hello,
        )
        await self._peer.start()

        # Return QR JSON
        qr_data = invite.to_qr_json()

        return web.json_response({
            "invite": qr_data,
            "topic": invite.topic[:16] + "...",
            "expires": invite.exp,
        })

    @require_auth
    async def _qr_handler(self, request: web.Request) -> web.Response:
        """
        Generate QR code image for current invite.

        Returns SVG QR code of the invite JSON.
        """
        if not self._current_invite or self._current_invite.is_expired:
            return web.Response(
                status=400,
                text="No active invite. POST /pair first.",
            )

        # Generate QR code
        qr_data = json.dumps(self._current_invite.to_qr_json())

        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_L,
            box_size=10,
            border=4,
        )
        qr.add_data(qr_data)
        qr.make(fit=True)

        # Generate SVG
        img = qr.make_image(image_factory=qrcode.image.svg.SvgImage)
        svg_buffer = io.BytesIO()
        img.save(svg_buffer)
        svg_data = svg_buffer.getvalue()

        return web.Response(
            body=svg_data,
            content_type="image/svg+xml",
        )

    async def start(self) -> None:
        """Start the HTTP server."""
        # Check for existing pair and reconnect
        if self._state.is_paired():
            stable_topic = self._state.get_stable_topic()
            if stable_topic:
                logger.info(f"Reconnecting on stable topic: {stable_topic[:16]}...")
                signaling = NtfySignaling(
                    self._config.ntfy_internal_url,
                    stable_topic,
                )
                self._peer = WebRtcPeer(
                    signaling=signaling,
                    stun_server=self._config.stun_server,
                    protocol_config=self._protocol_config,
                )
                await self._peer.start()
        elif self._state.get_invite():
            # Have invite but not paired yet
            invite = self._state.get_invite()
            if invite and not invite.is_expired:
                self._current_invite = invite
                logger.info(f"Resuming invite topic: {invite.topic[:16]}...")
                signaling = NtfySignaling(
                    self._config.ntfy_internal_url,
                    invite.topic,
                )
                self._peer = WebRtcPeer(
                    signaling=signaling,
                    stun_server=self._config.stun_server,
                    protocol_config=self._protocol_config,
                    on_hello=lambda pp: self._state.mark_paired(pp),
                )
                await self._peer.start()

        runner = web.AppRunner(self._app)
        await runner.setup()
        site = web.TCPSite(runner, "0.0.0.0", self._config.port)
        await site.start()
        logger.info(f"HTTP server listening on port {self._config.port}")

    async def stop(self) -> None:
        """Stop the server."""
        if self._peer:
            await self._peer.stop()
