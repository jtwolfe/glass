"""Persistent state for glass-peer pairing."""

import json
import secrets
import threading
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from mint import Invite


def codes_equal(left: str, right: str) -> bool:
    if not isinstance(left, str) or not isinstance(right, str):
        return False
    a = left.upper()
    b = right.upper()
    if len(a) != len(b):
        return False
    try:
        return secrets.compare_digest(a.encode("ascii"), b.encode("ascii"))
    except UnicodeEncodeError:
        return False


@dataclass
class PairState:
    """Persistent pairing state."""

    # Current invite (may be expired)
    invite: Invite | None = None

    # Paired phone peer ID (52-char base32)
    phone_peer: str | None = None

    # When pairing was established
    paired_at: str | None = None

    session_id: str | None = None
    reply_seq: int = 0
    code_consumed: bool = False

    @property
    def is_paired(self) -> bool:
        """Check if a phone is paired."""
        return self.phone_peer is not None

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
        result: dict = {
            "reply_seq": self.reply_seq,
            "code_consumed": self.code_consumed,
        }
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
        if self.session_id:
            result["session_id"] = self.session_id
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
        state.session_id = data.get("session_id")
        state.reply_seq = int(data.get("reply_seq") or 0)
        if "code_consumed" in data:
            state.code_consumed = bool(data.get("code_consumed"))
        else:
            state.code_consumed = bool(state.phone_peer)
        return state


class StateStore:
    """Thread-safe persistent state store."""

    def __init__(self, data_dir: str):
        self._data_dir = Path(data_dir)
        self._data_dir.mkdir(parents=True, exist_ok=True)
        self._state_file = self._data_dir / "state.json"
        self._lock = threading.Lock()
        self._state: PairState = self._load()
        self._session_wipe: Callable[[], None] | None = None

    @property
    def data_dir(self) -> Path:
        return self._data_dir

    def bind_session_log(self, clear: Callable[[], None]) -> None:
        """Remint / rotate must wipe the session log (call after releasing lock)."""
        self._session_wipe = clear

    def _fire_session_wipe(self) -> None:
        hook = self._session_wipe
        if hook is not None:
            hook()

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
            self._state.session_id = str(uuid.uuid4())
            self._state.reply_seq = 0
            self._state.code_consumed = False
            self._save()
        self._fire_session_wipe()

    def mark_paired(self, phone_peer: str) -> None:
        """Mark as paired with phone."""
        with self._lock:
            self._state.mark_paired(phone_peer)
            self._save()

    def rotate_session(self) -> str:
        """Mint a new session_id and reset reply_seq. Pair identity is kept."""
        with self._lock:
            self._state.session_id = str(uuid.uuid4())
            self._state.reply_seq = 0
            self._save()
            sid = self._state.session_id
        self._fire_session_wipe()
        return sid

    def ensure_session_id(self) -> str:
        """Mint session_id if missing. Does not reset reply_seq or wipe the log."""
        with self._lock:
            if not self._state.session_id:
                self._state.session_id = str(uuid.uuid4())
                self._save()
            return self._state.session_id

    def set_reply_seq(self, seq: int) -> None:
        """Write reply_seq (SessionLog allocate / bind repair)."""
        with self._lock:
            self._state.reply_seq = int(seq)
            self._save()

    def try_consume_pair(
        self, accepted_code: str, hello_peer: str
    ) -> tuple[bool, str | None]:
        """Consume the current invite on a just-paired hello. Atomic."""
        with self._lock:
            invite = self._state.invite
            if invite is None or not codes_equal(accepted_code, invite.code):
                return False, "rejected"
            if self._state.code_consumed:
                return False, "rejected"
            if self._state.phone_peer and self._state.phone_peer != hello_peer:
                return False, "wrong_peer"
            self._state.code_consumed = True
            self._state.mark_paired(hello_peer)
            self._save()
            return True, None

    def check_reconnect(self, hello_peer: str) -> tuple[bool, str | None]:
        """Reconnect hello: require stored phone_peer. Never mark_paired."""
        with self._lock:
            phone = self._state.phone_peer
            if not phone:
                return False, "unpaired"
            if phone != hello_peer:
                return False, "wrong_peer"
            return True, None

    def take_reply_seq(self) -> int:
        """Return the next reply seq and increment."""
        with self._lock:
            seq = self._state.reply_seq
            self._state.reply_seq += 1
            self._save()
            return seq

    def get_invite(self) -> Invite | None:
        """Get current invite."""
        with self._lock:
            return self._state.invite

    def get_phone_peer(self) -> str | None:
        """Get paired phone peer ID."""
        with self._lock:
            return self._state.phone_peer

    def get_session_id(self) -> str | None:
        """Get current plugin session id."""
        with self._lock:
            return self._state.session_id

    def get_reply_seq(self) -> int:
        """Get the first unused reply seq (does not increment)."""
        with self._lock:
            return self._state.reply_seq

    def is_code_consumed(self) -> bool:
        """Whether the current invite code has been consumed by a hello."""
        with self._lock:
            return self._state.code_consumed

    def is_paired(self) -> bool:
        """Check if paired."""
        with self._lock:
            return self._state.is_paired
