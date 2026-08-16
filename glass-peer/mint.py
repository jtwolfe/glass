"""Invite minting and topic computation for glass-pair v1."""

import hashlib
import secrets
import time
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta


# Crockford Base32 alphabet (no I, L, O, U to avoid confusion)
CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

# RFC 4648 Base32 alphabet for peer ID
BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

VERSION_PREFIX = "glass-pair/v1"


def crockford_encode(data: bytes, length: int) -> str:
    """Encode bytes to Crockford Base32 string of specified length."""
    result = []
    value = int.from_bytes(data, "big")
    for _ in range(length):
        result.append(CROCKFORD_ALPHABET[value & 0x1F])
        value >>= 5
    return "".join(reversed(result))


def base32_encode(data: bytes) -> str:
    """Encode bytes to RFC 4648 Base32 (lowercase, unpadded)."""
    result = []
    buffer = 0
    bits_left = 0
    for byte in data:
        buffer = (buffer << 8) | byte
        bits_left += 8
        while bits_left >= 5:
            bits_left -= 5
            result.append(BASE32_ALPHABET[(buffer >> bits_left) & 0x1F])
    if bits_left > 0:
        result.append(BASE32_ALPHABET[(buffer << (5 - bits_left)) & 0x1F])
    return "".join(result)


def compute_topic(peer: str, pub: str, code: str) -> str:
    """
    Compute ntfy topic hash.

    Topic = lowercase hex of SHA-256("glass-pair/v1\\n{peer}\\n{pub}\\n{code}")

    This matches the Android NtfySignaling.computeInviteTopic() exactly.
    """
    input_str = f"{VERSION_PREFIX}\n{peer}\n{pub}\n{code}"
    digest = hashlib.sha256(input_str.encode("utf-8")).digest()
    return digest.hex().lower()


def compute_stable_topic(plugin_peer: str, phone_peer: str) -> str:
    """
    Compute stable reconnect topic.

    Topic = lowercase hex of SHA-256("glass-pair/v1\\n{plugin_peer}\\n{phone_peer}")

    This matches PairingStore.computeStableTopic() on Android.
    """
    input_str = f"{VERSION_PREFIX}\n{plugin_peer}\n{phone_peer}"
    digest = hashlib.sha256(input_str.encode("utf-8")).digest()
    return digest.hex().lower()


@dataclass
class Invite:
    """Glass-pair v1 invite."""

    version: int  # Always 1
    peer: str  # 52-char lowercase base32 (SHA-256 of pub)
    pub: str  # 64 hex chars (public key / random identity)
    code: str  # 8-char Crockford Base32 invite code
    exp: str  # ISO-8601 expiry timestamp

    @property
    def topic(self) -> str:
        """Compute ntfy topic for this invite."""
        return compute_topic(self.peer, self.pub, self.code)

    @property
    def is_expired(self) -> bool:
        """Check if invite has expired."""
        try:
            exp_dt = datetime.fromisoformat(self.exp.replace("Z", "+00:00"))
            return datetime.now(timezone.utc) > exp_dt
        except (ValueError, AttributeError):
            return True

    def to_qr_json(self) -> dict:
        """
        Return QR JSON payload.

        Format: {"v":1,"peer":"...","pub":"...","code":"...","exp":"..."}

        No host, no IP - that's in the phone's ntfy settings.
        """
        return {
            "v": self.version,
            "peer": self.peer,
            "pub": self.pub,
            "code": self.code,
            "exp": self.exp,
        }


def mint_invite(ttl_seconds: int = 300) -> Invite:
    """
    Generate a new glass-pair v1 invite.

    - pub: 32 random bytes → 64 hex chars
    - peer: SHA-256(pub bytes) → base32 → 52 chars
    - code: 5 random bytes → Crockford Base32 → 8 chars
    - exp: now + ttl_seconds, ISO-8601

    Returns an Invite object.
    """
    pub_bytes = secrets.token_bytes(32)
    pub_hex = pub_bytes.hex().lower()

    peer_hash = hashlib.sha256(pub_bytes).digest()
    peer_b32 = base32_encode(peer_hash)

    code_bytes = secrets.token_bytes(5)
    code = crockford_encode(code_bytes, 8)

    exp_dt = datetime.now(timezone.utc) + timedelta(seconds=ttl_seconds)
    exp = exp_dt.isoformat(timespec="seconds").replace("+00:00", "Z")

    return Invite(
        version=1,
        peer=peer_b32,
        pub=pub_hex,
        code=code,
        exp=exp,
    )
