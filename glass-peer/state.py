"""Persistent state for glass-peer pairing."""

import json
import os
import threading
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from mint import Invite, compute_stable_topic


@dataclass
class PairState:
    """Persistent pairing state."""

    # Current invite (may be expired)
    invite: Optional[Invite] = None

    # Paired phone peer ID (52-char base32)
    phone_peer: Optional[str] = None

    # When pairing was established
    paired_at: Optional[str] = None

    @property
    def is_paired(self) -> bool:
        """Check if a phone is paired."""
        return self.phone_peer is not None

    @property
    def stable_topic(self) -> Optional[str]:
        """Compute stable reconnect topic if paired."""
        if not self.is_paired or not self.invite:
            return None
        return compute_stable_topic(self.invite.peer, self.phone_peer)

    def mark_paired(self, phone_peer: str) -> None:
        """Mark as paired with the given phone peer."""
        self.phone_peer = phone_peer
        self.paired_at = datetime.now(timezone.utc).isoformat(timespec="seconds")

    def clear_pair(self) -> None:
        """Clear pairing state (for remint)."""
        self.phone_peer = None
        self.paired_at = None

    def to_dict(self) -> dict:
        """Convert to JSON-serializable dict."""
        result = {}
        if self.invite:
            result["invite"] = {
                "version": self.invite.version,
                "peer": self.invite.peer,
                "pub": self.invite.pub,
                "code": self.invite.code,
                "exp": self.invite.exp,
            }
        if self.phone_peer:
            result["phone_peer"] = self.phone_peer
        if self.paired_at:
            result["paired_at"] = self.paired_at
        return result

    @classmethod
    def from_dict(cls, data: dict) -> "PairState":
        """Load from dict."""
        state = cls()
        if "invite" in data:
            inv = data["invite"]
            state.invite = Invite(
                version=inv.get("version", 1),
                peer=inv.get("peer", ""),
                pub=inv.get("pub", ""),
                code=inv.get("code", ""),
                exp=inv.get("exp", ""),
            )
        state.phone_peer = data.get("phone_peer")
        state.paired_at = data.get("paired_at")
        return state


class StateStore:
    """Thread-safe persistent state store."""

    def __init__(self, data_dir: str):
        self._data_dir = Path(data_dir)
        self._data_dir.mkdir(parents=True, exist_ok=True)
        self._state_file = self._data_dir / "state.json"
        self._lock = threading.Lock()
        self._state: PairState = self._load()

    def _load(self) -> PairState:
        """Load state from disk."""
        if not self._state_file.exists():
            return PairState()
        try:
            with open(self._state_file, "r") as f:
                data = json.load(f)
            return PairState.from_dict(data)
        except (json.JSONDecodeError, KeyError, TypeError):
            return PairState()

    def _save(self) -> None:
        """Save state to disk (must hold lock)."""
        with open(self._state_file, "w") as f:
            json.dump(self._state.to_dict(), f, indent=2)

    @property
    def state(self) -> PairState:
        """Get current state (read-only view)."""
        with self._lock:
            return self._state

    def set_invite(self, invite: Invite) -> None:
        """Set current invite, clearing any existing pair."""
        with self._lock:
            self._state.invite = invite
            self._state.clear_pair()
            self._save()

    def mark_paired(self, phone_peer: str) -> None:
        """Mark as paired with phone."""
        with self._lock:
            self._state.mark_paired(phone_peer)
            self._save()

    def get_invite(self) -> Optional[Invite]:
        """Get current invite."""
        with self._lock:
            return self._state.invite

    def get_phone_peer(self) -> Optional[str]:
        """Get paired phone peer ID."""
        with self._lock:
            return self._state.phone_peer

    def get_stable_topic(self) -> Optional[str]:
        """Get stable reconnect topic if paired."""
        with self._lock:
            return self._state.stable_topic

    def is_paired(self) -> bool:
        """Check if paired."""
        with self._lock:
            return self._state.is_paired
