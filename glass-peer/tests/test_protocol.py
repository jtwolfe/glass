"""Tests for DataChannel protocol handler."""

import pytest
import json

import sys
sys.path.insert(0, '..')

from protocol import ProtocolHandler, ProtocolConfig, Agent, Message


@pytest.fixture
def handler():
    """Create a test protocol handler."""
    config = ProtocolConfig(
        agents=[
            Agent(id="agent-1", name="Test Agent"),
        ]
    )
    return ProtocolHandler(config)


@pytest.mark.asyncio
async def test_handle_hello(handler):
    """Hello should be accepted and store phone_peer."""
    phone_peer = "a" * 52
    msg = json.dumps({"op": "hello", "peer": phone_peer})
    
    result = await handler.handle_message(msg)
    
    # Hello doesn't return a response
    assert result is None
    assert handler.phone_peer == phone_peer


@pytest.mark.asyncio
async def test_handle_hello_invalid_peer(handler):
    """Hello with invalid peer should return error."""
    msg = json.dumps({"op": "hello", "peer": "short"})
    
    result = await handler.handle_message(msg)
    
    resp = json.loads(result)
    assert resp["ok"] is False
    assert "error" in resp


@pytest.mark.asyncio
async def test_handle_send(handler):
    """Send should echo the message back."""
    msg = json.dumps({
        "v": 1,
        "op": "send",
        "from": "jamie",
        "text": "Hello world",
        "at": "2024-01-01T00:00:00Z",
    })
    
    result = await handler.handle_message(msg)
    
    resp = json.loads(result)
    assert resp["ok"] is True
    assert resp["v"] == 1
    assert resp["from"] == "jamie"
    assert resp["text"] == "Hello world"
    assert "id" in resp


@pytest.mark.asyncio
async def test_handle_agents(handler):
    """Agents request should return configured agents."""
    msg = json.dumps({"v": 1, "op": "agents"})
    
    result = await handler.handle_message(msg)
    
    resp = json.loads(result)
    assert resp["ok"] is True
    assert len(resp["agents"]) == 1
    assert resp["agents"][0]["name"] == "Test Agent"


@pytest.mark.asyncio
async def test_handle_agents_default():
    """Agents request with no config should return default."""
    config = ProtocolConfig()
    handler = ProtocolHandler(config)
    msg = json.dumps({"v": 1, "op": "agents"})
    
    result = await handler.handle_message(msg)
    
    resp = json.loads(result)
    assert resp["ok"] is True
    assert len(resp["agents"]) == 1
    assert resp["agents"][0]["name"] == "Assistant"


@pytest.mark.asyncio
async def test_handle_replies_empty(handler):
    """Replies with no messages should return empty list."""
    msg = json.dumps({"v": 1, "op": "replies", "after": "", "limit": 10})
    
    result = await handler.handle_message(msg)
    
    resp = json.loads(result)
    assert resp["ok"] is True
    assert resp["messages"] == []


@pytest.mark.asyncio
async def test_handle_unknown_op(handler):
    """Unknown op should return error."""
    msg = json.dumps({"op": "unknown"})
    
    result = await handler.handle_message(msg)
    
    resp = json.loads(result)
    assert resp["ok"] is False


@pytest.mark.asyncio
async def test_handle_invalid_json(handler):
    """Invalid JSON should return error."""
    result = await handler.handle_message("not json")
    
    resp = json.loads(result)
    assert resp["ok"] is False
