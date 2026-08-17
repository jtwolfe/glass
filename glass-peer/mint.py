"""Invite minting and topic computation for glass-pair v1."""

import hashlib
import io
import json
import re
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Crockford Base32 alphabet (no I, L, O, U to avoid confusion)
CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

# Phone parseV1 must accept every minted code. Exclude I L O U; 0 and 1 are valid.
# Keep in lockstep with PairingInvite.kt: ^[0-9A-HJKMNP-TV-Z]{8}$
PHONE_CROCKFORD_8_REGEX = re.compile(r"^[0-9A-HJKMNP-TV-Z]{8}$", re.IGNORECASE)

# RFC 4648 Base32 alphabet for peer ID
BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"


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


@dataclass
class Invite:
    """Glass-pair v1 invite."""

    version: int  # Always 1
    peer: str  # 52-char lowercase base32 (SHA-256 of pub)
    pub: str  # 64 hex chars (public key / random identity)
    code: str  # 8-char Crockford Base32 invite code
    exp: str  # ISO-8601 expiry timestamp

    @property
    def is_expired(self) -> bool:
        """Check if invite has expired."""
        try:
            exp_dt = datetime.fromisoformat(self.exp.replace("Z", "+00:00"))
            return datetime.now(timezone.utc) > exp_dt
        except (ValueError, AttributeError):
            return True

    def to_qr_json(self, wss: str | None = None) -> dict:
        """
        Return QR JSON payload.

        Format: {"v":1,"peer":"...","pub":"...","code":"...","exp":"..."}
        Optional wss is a reachability hint only (never host/url).
        """
        payload = {
            "v": self.version,
            "peer": self.peer,
            "pub": self.pub,
            "code": self.code,
            "exp": self.exp,
        }
        if wss:
            payload["wss"] = wss
        return payload


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


def write_qr_svg(invite: Invite, dest: Path, wss: str | None = None) -> None:
    """Write an SVG QR of the invite JSON next to state.json."""
    import qrcode
    import qrcode.image.svg

    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    qr_data = json.dumps(invite.to_qr_json(wss=wss), separators=(",", ":"))
    qr = qrcode.QRCode(
        version=1,
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=10,
        border=4,
    )
    qr.add_data(qr_data)
    qr.make(fit=True)
    img = qr.make_image(image_factory=qrcode.image.svg.SvgImage)
    buf = io.BytesIO()
    img.save(buf)
    dest.write_bytes(buf.getvalue())
