"""Environment configuration for glass-peer."""

import ipaddress
import os
import secrets
from dataclasses import dataclass, field
from urllib.parse import urlparse

_LOOPBACK_BINDS = frozenset({"127.0.0.1", "::1"})


def grokbot_gateway_present(path: str) -> bool:
    return os.path.isfile(os.path.expanduser(path))


def _default_grokbot_gateway() -> str:
    return os.path.expanduser("~/.grokbot/local-exec-daemon-connection.json")


def _default_grokbot_persist() -> str:
    return os.path.expanduser("~/.config/Grok Bot/sand-client-persistence")


@dataclass
class Config:
    """Glass-peer configuration from environment variables."""

    pair_username: str
    pair_password: str
    data_dir: str = "./data"
    http_bind: str = "127.0.0.1"
    port: int = 8080
    session_bind: str = "127.0.0.1"
    session_port: int = 8711
    session_path: str = "/session"
    session_max_candidates: int = 8
    public_wss_url: str | None = None
    invite_ttl_seconds: int = 300
    agent_backend: str | None = None
    agent_url: str | None = None
    agent_timeout_sec: float = 120.0
    agent_acp_cmd: str | None = None
    agent_acp_cwd: str | None = None
    agent_acp_name: str = "Grok"
    agent_acp_yolo: bool = True
    grokbot_gateway_path: str = field(default_factory=_default_grokbot_gateway)
    grokbot_persistence_dir: str = field(default_factory=_default_grokbot_persist)
    grokbot_poll_sec: float = 0.5
    grokbot_tail_limit: int = 50
    grokbot_tail_pages: int = 4
    session_log_max: int = 200
    session_log_max_bytes: int = 262144

    def backend_kind(self) -> str:
        """Resolve grokbot/acp/http/echo from explicit setting or auto-detect."""
        if self.agent_backend:
            return self.agent_backend
        if grokbot_gateway_present(self.grokbot_gateway_path):
            return "grokbot"
        if self.agent_acp_cmd:
            return "acp"
        if self.agent_url:
            return "http"
        return "echo"

    @classmethod
    def from_env(cls) -> "Config":
        """Load configuration from environment variables."""
        pair_username = os.environ.get("GLASS_PAIR_USERNAME", "")
        pair_password = os.environ.get("GLASS_PAIR_PASSWORD", "")

        if not pair_username or not pair_password:
            raise ValueError("GLASS_PAIR_USERNAME and GLASS_PAIR_PASSWORD must be set")

        session_bind = os.environ.get("GLASS_SESSION_BIND", "127.0.0.1")
        allow_nonlocal = os.environ.get("GLASS_SESSION_ALLOW_NONLOCAL", "") == "1"
        if session_bind not in _LOOPBACK_BINDS and not allow_nonlocal:
            raise ValueError(
                "GLASS_SESSION_BIND must be 127.0.0.1 or ::1 "
                "(set GLASS_SESSION_ALLOW_NONLOCAL=1 to override)"
            )

        public_wss = os.environ.get("GLASS_PUBLIC_WSS_URL", "").strip() or None
        if public_wss:
            public_wss = parse_public_wss_url(public_wss)

        session_path = (
            os.environ.get("GLASS_SESSION_PATH", "/session").strip() or "/session"
        )
        if not session_path.startswith("/"):
            session_path = "/" + session_path

        backend = os.environ.get("GLASS_AGENT_BACKEND", "").strip() or None
        agent_url = os.environ.get("GLASS_AGENT_URL", "").strip() or None
        acp_cmd = os.environ.get("GLASS_AGENT_ACP_CMD", "").strip() or None
        acp_cwd = os.environ.get("GLASS_AGENT_ACP_CWD", "").strip() or None
        acp_name = os.environ.get("GLASS_AGENT_ACP_NAME", "").strip() or "Grok"
        grokbot_gateway = (
            os.environ.get("GLASS_GROKBOT_GATEWAY_PATH", "").strip()
            or _default_grokbot_gateway()
        )
        grokbot_persist = (
            os.environ.get("GLASS_GROKBOT_PERSISTENCE_DIR", "").strip()
            or _default_grokbot_persist()
        )

        return cls(
            pair_username=pair_username,
            pair_password=pair_password,
            data_dir=os.environ.get("GLASS_DATA_DIR", "/data"),
            http_bind=os.environ.get("GLASS_HTTP_BIND", "127.0.0.1"),
            port=int(os.environ.get("GLASS_PORT", "8080")),
            session_bind=session_bind,
            session_port=int(os.environ.get("GLASS_SESSION_PORT", "8711")),
            session_path=session_path,
            session_max_candidates=int(
                os.environ.get("GLASS_SESSION_MAX_CANDIDATES", "8")
            ),
            public_wss_url=public_wss,
            invite_ttl_seconds=int(os.environ.get("GLASS_INVITE_TTL", "300")),
            agent_backend=backend,
            agent_url=agent_url,
            agent_timeout_sec=float(os.environ.get("GLASS_AGENT_TIMEOUT_SEC", "120")),
            agent_acp_cmd=acp_cmd,
            agent_acp_cwd=acp_cwd,
            agent_acp_name=acp_name,
            agent_acp_yolo=_env_flag("GLASS_AGENT_ACP_YOLO", True),
            grokbot_gateway_path=grokbot_gateway,
            grokbot_persistence_dir=grokbot_persist,
            grokbot_poll_sec=float(os.environ.get("GLASS_GROKBOT_POLL_SEC", "0.5")),
            grokbot_tail_limit=int(os.environ.get("GLASS_GROKBOT_TAIL_LIMIT", "50")),
            grokbot_tail_pages=int(os.environ.get("GLASS_GROKBOT_TAIL_PAGES", "4")),
            session_log_max=int(os.environ.get("GLASS_SESSION_LOG_MAX", "200")),
            session_log_max_bytes=int(
                os.environ.get("GLASS_SESSION_LOG_MAX_BYTES", "262144")
            ),
        )


def _env_flag(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    text = raw.strip().lower()
    if text == "":
        return default
    if text in {"1", "true", "yes", "on"}:
        return True
    if text in {"0", "false", "no", "off"}:
        return False
    return default


def parse_public_wss_url(raw: str) -> str:
    """Validate a QR/settings session hint.

    wss:// is always allowed (except loopback). ws:// is LAN/private IPs only.
    127.0.0.1 is rejected — on a phone that is the phone, not this plugin.
    """
    text = raw.strip()

    parsed = urlparse(text)
    scheme = parsed.scheme.lower()
    if scheme not in {"wss", "ws"}:
        raise ValueError("GLASS_PUBLIC_WSS_URL must use ws or wss")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("GLASS_PUBLIC_WSS_URL must not include userinfo")
    if parsed.query or parsed.fragment:
        raise ValueError("GLASS_PUBLIC_WSS_URL must not include query or fragment")
    host = parsed.hostname or ""
    if not host:
        raise ValueError("GLASS_PUBLIC_WSS_URL host is invalid")
    if _is_loopback_host(host):
        raise ValueError("GLASS_PUBLIC_WSS_URL must not be 127.0.0.1 or localhost")
    if scheme == "ws" and not _is_private_lan_host(host):
        raise ValueError("ws:// is only allowed for a LAN or private IP")

    path = parsed.path or ""
    if path in ("", "/"):
        path = "/session"

    return f"{scheme}://{parsed.netloc}{path}"


def _is_loopback_host(host: str) -> bool:
    if host.lower() in {"localhost", "127.0.0.1", "::1"}:
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _is_private_lan_host(host: str) -> bool:
    try:
        addr = ipaddress.ip_address(host)
    except ValueError:
        return False
    if addr.is_loopback:
        return False
    return addr.is_private or addr in ipaddress.ip_network("100.64.0.0/10")


def generate_random_bytes(n: int) -> bytes:
    """Generate cryptographically secure random bytes."""
    return secrets.token_bytes(n)
