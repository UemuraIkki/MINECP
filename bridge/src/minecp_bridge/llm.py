"""Ollama ``/api/chat`` client: tool-calling skill selection with schema
validation, bounded retries, and a safe fallback (仕様書§4.2.4, §10).
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx
from pydantic import ValidationError

from .config import BridgeConfig
from .messages import (
    SKILL_ARGS_CLASSES,
    SkillName,
    _SkillCommandBase,
    build_skill_command,
)
from .prompts import SKILL_DESCRIPTIONS, build_tool_error_feedback

logger = logging.getLogger(__name__)

FALLBACK_SKILL = SkillName.goto
FALLBACK_ARGS: dict[str, Any] = {"target": "base"}


class ExchangeLogger(Protocol):
    def log_llm_exchange(self, messages: list[dict[str, Any]], response: dict[str, Any] | None, error: str | None) -> None: ...


def build_tools() -> list[dict[str, Any]]:
    """Ollama/OpenAI-style tool definitions, one per skill, derived from the
    same Pydantic args models used to validate skill_command messages."""

    tools = []
    for skill, args_cls in SKILL_ARGS_CLASSES.items():
        schema = args_cls.model_json_schema()
        schema.pop("title", None)
        tools.append(
            {
                "type": "function",
                "function": {
                    "name": skill.value,
                    "description": SKILL_DESCRIPTIONS.get(skill, skill.value),
                    "parameters": schema,
                },
            }
        )
    return tools


@dataclass
class LLMDecision:
    command: _SkillCommandBase
    attempts: int
    used_fallback: bool
    raw_responses: list[dict[str, Any]] = field(default_factory=list)


class LLMOutputError(Exception):
    """A single attempt's tool call was missing or failed schema validation."""


def _extract_tool_call(response: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    message = response.get("message") or {}
    tool_calls = message.get("tool_calls") or []
    if not tool_calls:
        raise LLMOutputError("No tool_calls in LLM response; a plain-text reply is not acceptable.")
    call = tool_calls[0]
    function = call.get("function") or {}
    name = function.get("name")
    raw_args = function.get("arguments", {})
    if isinstance(raw_args, str):
        try:
            raw_args = json.loads(raw_args) if raw_args else {}
        except json.JSONDecodeError as exc:
            raise LLMOutputError(f"tool_call arguments were not valid JSON: {exc}") from exc
    if not isinstance(raw_args, dict):
        raise LLMOutputError("tool_call arguments must be a JSON object.")
    if name is None:
        raise LLMOutputError("tool_call is missing a function name.")
    return name, raw_args


def _validate_skill_call(name: str, raw_args: dict[str, Any]) -> tuple[SkillName, dict[str, Any]]:
    try:
        skill = SkillName(name)
    except ValueError as exc:
        valid = ", ".join(s.value for s in SkillName)
        raise LLMOutputError(f"Unknown skill '{name}'. Valid skills: {valid}") from exc

    args_cls = SKILL_ARGS_CLASSES[skill]
    try:
        validated = args_cls.model_validate(raw_args)
    except ValidationError as exc:
        raise LLMOutputError(f"Arguments for '{name}' failed validation: {exc}") from exc

    return skill, validated.model_dump(mode="json")


async def _post_chat(client: httpx.AsyncClient, config: BridgeConfig, messages: list[dict[str, Any]], tools: list[dict[str, Any]]) -> dict[str, Any]:
    resp = await client.post(
        f"{config.ollama_url}/api/chat",
        json={"model": config.ollama_model, "messages": messages, "tools": tools, "stream": False},
        timeout=config.ollama_timeout_s,
    )
    resp.raise_for_status()
    return resp.json()


def build_fallback_command(*, seq: int) -> _SkillCommandBase:
    """The safe default action when the LLM cannot produce a valid skill call
    after all retries (仕様書§4.2.4, §10): return to base."""

    return build_skill_command(FALLBACK_SKILL, FALLBACK_ARGS, seq=seq)


async def decide_skill(
    client: httpx.AsyncClient,
    config: BridgeConfig,
    system_prompt: str,
    user_prompt: str,
    *,
    seq: int,
    exchange_logger: ExchangeLogger | None = None,
) -> LLMDecision:
    """Ask the LLM for the next skill call, validating and retrying up to
    ``config.max_llm_retries`` times, falling back to ``goto base`` after
    exhausting retries (仕様書§4.2.4)."""

    messages: list[dict[str, Any]] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]
    tools = build_tools()
    raw_responses: list[dict[str, Any]] = []

    attempts = 0
    last_error: str | None = None
    while attempts < config.max_llm_retries:
        attempts += 1
        try:
            response = await _post_chat(client, config, messages, tools)
        except httpx.HTTPError as exc:
            last_error = f"HTTP error contacting Ollama: {exc}"
            if exchange_logger is not None:
                exchange_logger.log_llm_exchange(messages, None, last_error)
            continue

        raw_responses.append(response)
        if exchange_logger is not None:
            exchange_logger.log_llm_exchange(messages, response, None)

        try:
            name, raw_args = _extract_tool_call(response)
            skill, validated_args = _validate_skill_call(name, raw_args)
        except LLMOutputError as exc:
            last_error = str(exc)
            assistant_content = (response.get("message") or {}).get("content", "")
            messages.append({"role": "assistant", "content": assistant_content})
            messages.append({"role": "user", "content": build_tool_error_feedback([last_error])})
            continue

        command = build_skill_command(skill, validated_args, seq=seq)
        return LLMDecision(command=command, attempts=attempts, used_fallback=False, raw_responses=raw_responses)

    logger.warning("LLM failed to produce a valid skill call after %d attempts (last error: %s); falling back to goto base.", attempts, last_error)
    return LLMDecision(
        command=build_fallback_command(seq=seq),
        attempts=attempts,
        used_fallback=True,
        raw_responses=raw_responses,
    )
