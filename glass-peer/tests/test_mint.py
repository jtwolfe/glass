"""Tests for invite minting."""

import sys

sys.path.insert(0, "..")

from mint import (
    PHONE_CROCKFORD_8_REGEX,
    base32_encode,
    crockford_encode,
    mint_invite,
)


def test_base32_encode_32_bytes():
    """32 bytes should produce 52 base32 characters."""
    data = bytes(range(32))
    result = base32_encode(data)
    assert len(result) == 52
    assert all(c in "abcdefghijklmnopqrstuvwxyz234567" for c in result)


def test_crockford_encode_5_bytes():
    """5 bytes should produce 8 Crockford characters."""
    data = b"\xff\xff\xff\xff\xff"
    result = crockford_encode(data, 8)
    assert len(result) == 8
    assert all(c in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" for c in result)


def test_mint_invite_fields():
    """Minted invite should have all required fields."""
    invite = mint_invite(ttl_seconds=60)

    assert invite.version == 1
    assert len(invite.peer) == 52
    assert len(invite.pub) == 64
    assert len(invite.code) == 8
    assert invite.exp is not None


def test_mint_invite_not_expired():
    """Fresh invite should not be expired."""
    invite = mint_invite(ttl_seconds=60)
    assert not invite.is_expired


def test_qr_json_format():
    """QR JSON should have correct format."""
    invite = mint_invite()
    qr = invite.to_qr_json()

    assert qr["v"] == 1
    assert "peer" in qr
    assert "pub" in qr
    assert "code" in qr
    assert "exp" in qr
    # No host or IP in QR; wss only when configured
    assert "host" not in qr
    assert "url" not in qr
    assert "wss" not in qr


def test_qr_json_optional_wss():
    invite = mint_invite()
    qr = invite.to_qr_json(wss="wss://chat.example.com/session")
    assert qr["wss"] == "wss://chat.example.com/session"
    assert "host" not in qr
    assert "url" not in qr


def test_mint_code_matches_phone_crockford():
    """Every minted code must survive the phone Crockford regex (0/1 allowed)."""
    assert PHONE_CROCKFORD_8_REGEX.match("F41XS71T")
    assert PHONE_CROCKFORD_8_REGEX.match("01ABCDEF")
    assert not PHONE_CROCKFORD_8_REGEX.match("I2345678")
    assert not PHONE_CROCKFORD_8_REGEX.match("L2345678")
    assert not PHONE_CROCKFORD_8_REGEX.match("O2345678")
    assert not PHONE_CROCKFORD_8_REGEX.match("U2345678")
    for _ in range(1000):
        invite = mint_invite()
        assert PHONE_CROCKFORD_8_REGEX.match(invite.code)
