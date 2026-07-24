"""Requirement (3): LLM output validation, retry, and fallback — Ollama is
mocked via httpx.MockTransport, no real Ollama/Minecraft needed.
"""

from __future__ import annotations

import json

import httpx
import pytest

from minecp_bridge.config import BridgeConfig
from minecp_bridge.llm import FALLBACK_ARGS, FALLBACK_SKILL, decide_skill


def _ollama_response(name: str, arguments) -> dict:
    return {
        "message": {
            "role": "assistant",
            "content": "",
            "tool_calls": [{"function": {"name": name, "arguments": arguments}}],
        }
    }


def _plain_text_response(text: str) -> dict:
    return {"message": {"role": "assistant", "content": text, "tool_calls": []}}


class _ScriptedLogger:
    def __init__(self):
        self.exchanges = []

    def log_llm_exchange(self, messages, response, error):
        self.exchanges.append((messages, response, error))


@pytest.mark.asyncio
async def test_decide_skill_succeeds_on_first_valid_response():
    config = BridgeConfig(max_llm_retries=3)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_ollama_response("mine", {"block": "minecraft:iron_ore", "count": 3}))

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        decision = await decide_skill(client, config, "system", "situation", seq=1)

    assert decision.attempts == 1
    assert decision.used_fallback is False
    assert decision.command.skill.value == "mine"
    assert decision.command.args.block == "minecraft:iron_ore"
    assert decision.command.args.count == 3


@pytest.mark.asyncio
async def test_decide_skill_retries_then_succeeds():
    config = BridgeConfig(max_llm_retries=3)
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(200, json=_plain_text_response("I think I should mine iron."))
        if calls["n"] == 2:
            # unknown skill name
            return httpx.Response(200, json=_ollama_response("dig_hole", {}))
        return httpx.Response(200, json=_ollama_response("goto", {"target": "base"}))

    transport = httpx.MockTransport(handler)
    exchange_logger = _ScriptedLogger()
    async with httpx.AsyncClient(transport=transport) as client:
        decision = await decide_skill(
            client, config, "system", "situation", seq=1, exchange_logger=exchange_logger
        )

    assert calls["n"] == 3
    assert decision.attempts == 3
    assert decision.used_fallback is False
    assert decision.command.skill.value == "goto"
    assert len(exchange_logger.exchanges) == 3


@pytest.mark.asyncio
async def test_decide_skill_falls_back_after_exhausting_retries():
    config = BridgeConfig(max_llm_retries=3)

    def handler(request: httpx.Request) -> httpx.Response:
        # Always invalid: args fail schema validation for 'mine' (missing count)
        return httpx.Response(200, json=_ollama_response("mine", {"block": "minecraft:iron_ore"}))

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        decision = await decide_skill(client, config, "system", "situation", seq=7)

    assert decision.attempts == 3
    assert decision.used_fallback is True
    assert decision.command.skill.value == FALLBACK_SKILL.value
    assert decision.command.args.model_dump(mode="json") == FALLBACK_ARGS
    assert decision.command.seq == 7


@pytest.mark.asyncio
async def test_decide_skill_handles_string_encoded_arguments():
    config = BridgeConfig(max_llm_retries=3)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_ollama_response("equip", json.dumps({"item": "minecraft:iron_sword"})))

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        decision = await decide_skill(client, config, "system", "situation", seq=1)

    assert decision.command.skill.value == "equip"
    assert decision.command.args.item == "minecraft:iron_sword"


@pytest.mark.asyncio
async def test_decide_skill_falls_back_on_http_error():
    config = BridgeConfig(max_llm_retries=2)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": "internal"})

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        decision = await decide_skill(client, config, "system", "situation", seq=1)

    assert decision.used_fallback is True
    assert decision.command.skill.value == FALLBACK_SKILL.value
