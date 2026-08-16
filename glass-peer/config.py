"""Environment configuration for glass-peer."""

import os
import secrets
from dataclasses import dataclass


@dataclass
class Config:
    """Glass-peer configuration from environment variables."""

    # HTTP Basic auth for /pair and /qr endpoints
    pair_username: str
    pair_password: str

    # ntfy configuration
    # Internal URL used by peer to publish/subscribe (no path prefix needed)
    ntfy_internal_url: str
    # Public URL the phone uses (may include path prefix like /ntfy)
    ntfy_public_url: str

    # WebRTC STUN server
    stun_server: str

    # Persistence directory
    data_dir: str

    # Server port
    port: int

    # Invite expiry in seconds (default 5 minutes)
    invite_ttl_seconds: int

    @classmethod
    def from_env(cls) -> "Config":
        """Load configuration from environment variables."""
        pair_username = os.environ.get("GLASS_PAIR_USERNAME", "")
        pair_password = os.environ.get("GLASS_PAIR_PASSWORD", "")

        if not pair_username or not pair_password:
            raise ValueError(
                "GLASS_PAIR_USERNAME and GLASS_PAIR_PASSWORD must be set"
            )

        return cls(
            pair_username=pair_username,
            pair_password=pair_password,
            ntfy_internal_url=os.environ.get(
                "GLASS_NTFY_INTERNAL_URL", "http://ntfy:80"
            ),
            ntfy_public_url=os.environ.get(
                "GLASS_NTFY_PUBLIC_URL", "https://glass.example.com/ntfy"
            ),
            stun_server=os.environ.get(
                "GLASS_STUN_SERVER", "stun:stun.l.google.com:19302"
            ),
            data_dir=os.environ.get("GLASS_DATA_DIR", "/data"),
            port=int(os.environ.get("GLASS_PORT", "8080")),
            invite_ttl_seconds=int(os.environ.get("GLASS_INVITE_TTL", "300")),
        )


def generate_random_bytes(n: int) -> bytes:
    """Generate cryptographically secure random bytes."""
    return secrets.token_bytes(n)
