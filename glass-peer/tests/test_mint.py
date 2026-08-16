"""Tests for invite minting and topic computation."""


import sys

sys.path.insert(0, '..')

from mint import (
    base32_encode,
    compute_stable_topic,
    compute_topic,
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


def test_compute_topic_deterministic():
    """Topic computation should be deterministic."""
    peer = "a" * 52
    pub = "b" * 64
    code = "ABCD1234"
    
    topic1 = compute_topic(peer, pub, code)
    topic2 = compute_topic(peer, pub, code)
    
    assert topic1 == topic2
    assert len(topic1) == 64


def test_compute_stable_topic():
    """Stable topic should be computed from plugin and phone peers."""
    plugin_peer = "a" * 52
    phone_peer = "b" * 52
    
    topic = compute_stable_topic(plugin_peer, phone_peer)
    
    assert len(topic) == 64
    # Different from invite topic
    assert topic != compute_topic(plugin_peer, "c" * 64, "ABCD1234")


def test_qr_json_format():
    """QR JSON should have correct format."""
    invite = mint_invite()
    qr = invite.to_qr_json()
    
    assert qr["v"] == 1
    assert "peer" in qr
    assert "pub" in qr
    assert "code" in qr
    assert "exp" in qr
    # No host or IP in QR
    assert "host" not in qr
    assert "url" not in qr
