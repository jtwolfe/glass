"""DataChannel protocol handlers for glass-pair v1."""

import json
import logging
import uuid
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any, Callable, Awaitable

logger = logging.getLogger(__name__)


@dataclass
class Agent:
    """Peer-side agent that can receive messages."""

    id: str
    name: str


@dataclass
class Message:
    """Chat message."""

    id: str
    from_: str  # "jamie" or agent name
    text: str
    at: str  # ISO-8601
    agent_id: Optional[str] = None


@dataclass
class ProtocolConfig:
    """Protocol handler configuration."""

    # Available agents (can be updated dynamically)
    agents: List[Agent] = field(default_factory=list)

    # Default agent when none specified
    default_agent_id: Optional[str] = None

    # Callback for incoming messages
    on_message: Optional[Callable[[Message], Awaitable[None]]] = None


class ProtocolHandler:
    """
    DataChannel protocol handler for glass-pair v1.

    Handles JSON messages over WebRTC DataChannel:
    - {"op":"hello","peer":"<phone_peer_id>"}
    - {"v":1,"op":"send","from":"jamie","text":"...","at":"<ISO>","agentId":"<uuid>"}
    - {"v":1,"op":"replies","after":"<ISO>","limit":50}
    - {"v":1,"op":"agents"}
    """

    def __init__(
        self,
        config: ProtocolConfig,
        on_hello: Optional[Callable[[str], Awaitable[None]]] = None,
    ):
        """
        Initialize protocol handler.

        Args:
            config: Protocol configuration
            on_hello: Callback when hello received with phone_peer
        """
        self._config = config
        self._on_hello = on_hello
        self._messages: List[Message] = []
        self._phone_peer: Optional[str] = None

    @property
    def phone_peer(self) -> Optional[str]:
        """Get phone peer ID from hello message."""
        return self._phone_peer

    def set_agents(self, agents: List[Agent]) -> None:
        """Update available agents list."""
        self._config.agents = agents

    async def handle_message(self, data: str) -> Optional[str]:
        """
        Handle incoming DataChannel message.

        Args:
            data: UTF-8 JSON message string

        Returns:
            Response JSON string, or None if no response needed
        """
        try:
            msg = json.loads(data)
        except json.JSONDecodeError:
            return self._error_response("Invalid JSON")

        op = msg.get("op", "")

        if op == "hello":
            return await self._handle_hello(msg)
        elif op == "send":
            return await self._handle_send(msg)
        elif op == "replies":
            return await self._handle_replies(msg)
        elif op == "agents":
            return await self._handle_agents(msg)
        else:
            return self._error_response(f"Unknown op: {op}")

    async def _handle_hello(self, msg: dict) -> Optional[str]:
        """Handle hello message to establish stable topic."""
        phone_peer = msg.get("peer", "")
        if not phone_peer:
            return self._error_response("Missing peer in hello")

        # Validate: 52-char base32
        if len(phone_peer) != 52:
            return self._error_response("Invalid peer format")

        self._phone_peer = phone_peer
        logger.info(f"Hello from phone peer: {phone_peer[:16]}...")

        if self._on_hello:
            await self._on_hello(phone_peer)

        # Hello doesn't require a response per protocol
        return None

    async def _handle_send(self, msg: dict) -> str:
        """Handle send message from phone."""
        v = msg.get("v", -1)
        if v != 1:
            return self._error_response("Unsupported version")

        from_user = msg.get("from", "")
        text = msg.get("text", "")
        at = msg.get("at", "")
        agent_id = msg.get("agentId")

        if not from_user or not text:
            return self._error_response("Missing from or text")

        # Generate message ID
        msg_id = str(uuid.uuid4())

        message = Message(
            id=msg_id,
            from_=from_user,
            text=text,
            at=at or datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            agent_id=agent_id,
        )

        self._messages.append(message)

        # Notify callback
        if self._config.on_message:
            try:
                await self._config.on_message(message)
            except Exception as e:
                logger.error(f"Message callback error: {e}")

        # Echo back the message
        return json.dumps({
            "v": 1,
            "ok": True,
            "id": message.id,
            "from": message.from_,
            "text": message.text,
            "at": message.at,
        })

    async def _handle_replies(self, msg: dict) -> str:
        """Handle replies request (fetch messages after cursor)."""
        v = msg.get("v", -1)
        if v != 1:
            return self._error_response("Unsupported version")

        after = msg.get("after", "")
        limit = min(msg.get("limit", 50), 100)

        # Filter messages after cursor
        filtered = []
        for m in self._messages:
            if not after or m.at > after:
                filtered.append({
                    "id": m.id,
                    "from": m.from_,
                    "text": m.text,
                    "at": m.at,
                })
                if len(filtered) >= limit:
                    break

        return json.dumps({
            "v": 1,
            "ok": True,
            "messages": filtered,
        })

    async def _handle_agents(self, msg: dict) -> str:
        """Handle agents list request."""
        agents = []
        for agent in self._config.agents:
            agents.append({
                "id": agent.id,
                "name": agent.name,
            })

        # If no agents configured, return a generic default
        if not agents:
            agents.append({
                "id": "default",
                "name": "Assistant",
            })

        return json.dumps({
            "v": 1,
            "ok": True,
            "agents": agents,
        })

    def add_reply(self, from_agent: str, text: str, agent_id: Optional[str] = None) -> Message:
        """
        Add a reply from the peer side.

        Used for sending responses back through the channel.
        """
        message = Message(
            id=str(uuid.uuid4()),
            from_=from_agent,
            text=text,
            at=datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            agent_id=agent_id,
        )
        self._messages.append(message)
        return message

    def _error_response(self, error: str) -> str:
        """Create error response."""
        return json.dumps({
            "v": 1,
            "ok": False,
            "error": error,
        })
