"""Operator HTTP for glass-peer: /pair, /qr, /health on loopback only."""

import base64
import io
import json
import logging
import secrets
from functools import wraps
from pathlib import Path

import qrcode
import qrcode.image.svg
from aiohttp import web

from config import Config
from mint import mint_invite, write_qr_svg
from session import SessionServer, bound_port
from state import StateStore

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
        user_ok = secrets.compare_digest(req_user, username)
        pass_ok = secrets.compare_digest(req_pass, password)
        return user_ok and pass_ok
    except (ValueError, UnicodeDecodeError):
        return False


def require_auth(handler):
    """Decorator to require HTTP Basic auth on handler."""

    @wraps(handler)
    async def wrapper(*args):
        request = args[-1]
        config: Config = request.app["config"]
        if not check_basic_auth(request, config.pair_username, config.pair_password):
            return web.Response(
                status=401,
                headers={"WWW-Authenticate": 'Basic realm="glass-pair"'},
                text="Unauthorized",
            )
        return await handler(*args)

    return wrapper


class GlassServer:
    """Operator HTTP server (no session routes)."""

    def __init__(
        self,
        config: Config,
        state_store: StateStore,
        session: SessionServer | None = None,
        backend_name: str = "echo",
    ):
        self._config = config
        self._state = state_store
        self._session = session
        self._backend_name = backend_name
        self._app = web.Application()
        self._runner: web.AppRunner | None = None
        self._port = config.port
        self._setup_routes()

    def _setup_routes(self) -> None:
        """Set up HTTP routes."""
        self._app.router.add_get("/health", self._health_handler)
        self._app.router.add_get("/pair", self._pair_handler)
        self._app.router.add_post("/pair", self._pair_handler)
        self._app.router.add_get("/qr", self._qr_handler)
        self._app["config"] = self._config

    @property
    def port(self) -> int:
        return self._port

    async def _health_handler(self, request: web.Request) -> web.Response:
        """Health check endpoint (unauthenticated)."""
        connected = bool(self._session and self._session.has_live_session())
        return web.json_response(
            {
                "status": "ok",
                "paired": self._state.is_paired(),
                "connected": connected,
                "sessionPort": self._config.session_port,
                "sessionPath": self._config.session_path,
                "backend": self._backend_name,
            }
        )

    @require_auth
    async def _pair_handler(self, request: web.Request) -> web.Response:
        """Remint only: new identity, clear pair, kick live and candidates."""
        logger.info("Minting new invite (remint clears existing pair)")

        invite = mint_invite(self._config.invite_ttl_seconds)
        self._state.set_invite(invite)
        if self._session is not None:
            await self._session.kick()
            self._session._log.clear()

        write_qr_svg(
            invite,
            Path(self._config.data_dir) / "qr.svg",
            wss=self._config.public_wss_url,
        )
        qr_data = invite.to_qr_json(wss=self._config.public_wss_url)
        logger.info(f"QR {json.dumps(qr_data)}")

        return web.json_response(
            {
                "invite": qr_data,
                "expires": invite.exp,
            }
        )

    @require_auth
    async def _qr_handler(self, request: web.Request) -> web.Response:
        """Generate QR code image for current invite."""
        invite = self._state.get_invite()
        if not invite or invite.is_expired:
            return web.Response(
                status=400,
                text="No active invite. POST /pair first.",
            )

        qr_data = json.dumps(invite.to_qr_json(wss=self._config.public_wss_url))
        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_L,
            box_size=10,
            border=4,
        )
        qr.add_data(qr_data)
        qr.make(fit=True)
        img = qr.make_image(image_factory=qrcode.image.svg.SvgImage)
        svg_buffer = io.BytesIO()
        img.save(svg_buffer)

        return web.Response(
            body=svg_buffer.getvalue(),
            content_type="image/svg+xml",
        )

    async def start(self) -> None:
        """Start the HTTP server on the configured loopback bind."""
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self._config.http_bind, self._config.port)
        await site.start()
        if self._config.port == 0:
            self._port = bound_port(self._runner)
        else:
            self._port = self._config.port
        logger.info(f"HTTP server listening on {self._config.http_bind}:{self._port}")

    async def stop(self) -> None:
        """Stop the server."""
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
