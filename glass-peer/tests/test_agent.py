"""Tests for EchoBackend and HttpBackend."""

import asyncio

import pytest
from aiohttp import web

from agent import AgentUnavailable, EchoBackend, HttpBackend, select_backend
from grokbot import GrokBotBackend
from session import bound_port


@pytest.mark.asyncio
async def test_echo_complete_prefix():
    backend = EchoBackend()
    text = await backend.complete(text="hi", agent_id=None, phone_peer="a" * 52)
    assert text == "echo: hi"
    agents = await backend.list_agents()
    assert agents[0].id == "echo"
    assert agents[0].name == "Echo"


@pytest.mark.asyncio
async def test_http_backend_complete_ok():
    async def agents(request):
        return web.json_response({"agents": [{"id": "bot", "name": "Bot"}]})

    async def complete(request):
        body = await request.json()
        return web.json_response({"text": f"got:{body['text']}"})

    app = web.Application()
    app.router.add_get("/agents", agents)
    app.router.add_post("/complete", complete)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = bound_port(runner)
    try:
        backend = HttpBackend(f"http://127.0.0.1:{port}", timeout_sec=5)
        listed = await backend.list_agents()
        assert listed[0].id == "bot"
        text = await backend.complete(text="yo", agent_id=None, phone_peer="b" * 52)
        assert text == "got:yo"
        await backend.cancel()
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_http_backend_503_unavailable():
    async def complete(request):
        return web.json_response({"error": "unavailable"}, status=503)

    app = web.Application()
    app.router.add_post("/complete", complete)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = bound_port(runner)
    try:
        backend = HttpBackend(f"http://127.0.0.1:{port}", timeout_sec=5)
        with pytest.raises(AgentUnavailable):
            await backend.complete(text="x", agent_id=None, phone_peer="c" * 52)
        await backend.cancel()
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_http_backend_cancel_aborts_inflight():
    started = asyncio.Event()
    release = asyncio.Event()

    async def complete(request):
        started.set()
        await release.wait()
        return web.json_response({"text": "late"})

    app = web.Application()
    app.router.add_post("/complete", complete)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = bound_port(runner)
    backend = HttpBackend(f"http://127.0.0.1:{port}", timeout_sec=30)
    try:
        task = asyncio.create_task(
            backend.complete(text="x", agent_id=None, phone_peer="d" * 52)
        )
        await asyncio.wait_for(started.wait(), 2)
        await backend.cancel()
        with pytest.raises((AgentUnavailable, asyncio.CancelledError)):
            await asyncio.wait_for(task, 2)
    finally:
        release.set()
        await runner.cleanup()


def test_select_backend_echo_default():
    backend = select_backend(kind="echo", url=None, timeout_sec=1)
    assert backend.name == "echo"


def test_select_backend_acp_requires_cmd():
    with pytest.raises(ValueError, match="GLASS_AGENT_ACP_CMD"):
        select_backend(kind="acp", url=None, timeout_sec=1)


def test_select_backend_grokbot(tmp_path):
    backend = select_backend(
        kind="grokbot",
        url=None,
        timeout_sec=1,
        grokbot_gateway_path=str(tmp_path / "gateway.json"),
        grokbot_persistence_dir=str(tmp_path / "persist"),
    )
    assert backend.name == "grokbot"
    assert isinstance(backend, GrokBotBackend)
