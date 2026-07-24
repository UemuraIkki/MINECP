"""名前付き地点gotoのブリッジ側座標解決(ADR-0001の帰結)。

Modは名前付き地点を知らないため、ブリッジは送信前にNamedLocationを記憶座標へ
置換する。未知の名前は合成TARGET_NOT_FOUNDとして履歴に記録し、再決定→
フォールバック(goto base)の順で処理する。
"""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest

import minecp_bridge.agent_loop as agent_loop_module
from minecp_bridge.agent_loop import AgentLoop
from minecp_bridge.config import BridgeConfig
from minecp_bridge.llm import LLMDecision, build_skill_command
from minecp_bridge.logging_setup import get_session_logger
from minecp_bridge.messages import BlockPos, FailureCode, NamedLocation
from minecp_bridge.state import BridgeState

BASE = BlockPos(x=10, y=64, z=-20)


@pytest.fixture
async def loop(tmp_path: Path):
    config = BridgeConfig(state_dir=tmp_path, logs_dir=tmp_path)
    state = BridgeState()
    state.register_memory(NamedLocation.base, BASE)
    session_logger = get_session_logger(tmp_path)
    async with httpx.AsyncClient() as client:
        agent = AgentLoop(config, state, client, session_logger)

        sent = []

        async def fake_send_command(command):
            sent.append(command)
            return True

        agent.server.send_command = fake_send_command
        agent._test_sent = sent
        yield agent


def _install_decide(agent, skill_args_sequence):
    """decide_skillを、呼び出し毎にskill_args_sequenceを順に返すフェイクに差し替える。"""
    calls = []

    async def fake_decide_skill(*args, seq, exchange_logger=None, **kwargs):
        skill, skill_args = skill_args_sequence[min(len(calls), len(skill_args_sequence) - 1)]
        calls.append(seq)
        command = build_skill_command(skill, skill_args, seq=seq)
        return LLMDecision(command=command, attempts=1, used_fallback=False)

    agent_loop_module.decide_skill = fake_decide_skill
    return calls


@pytest.mark.asyncio
async def test_known_named_location_is_resolved_to_coordinates(loop: AgentLoop):
    _install_decide(loop, [("goto", {"target": "base"})])
    await loop._decide_and_act("periodic")

    assert len(loop._test_sent) == 1
    target = loop._test_sent[0].args.target
    assert isinstance(target, BlockPos)
    assert (target.x, target.y, target.z) == (BASE.x, BASE.y, BASE.z)


@pytest.mark.asyncio
async def test_coordinate_target_passes_through_unchanged(loop: AgentLoop):
    _install_decide(loop, [("goto", {"target": {"x": 1, "y": 2, "z": 3}})])
    await loop._decide_and_act("periodic")

    target = loop._test_sent[0].args.target
    assert (target.x, target.y, target.z) == (1, 2, 3)


@pytest.mark.asyncio
async def test_unknown_named_location_records_failure_then_retries(loop: AgentLoop):
    # 1回目: 未知のstronghold → 合成失敗を記録して再決定。2回目: 座標指定で成功。
    calls = _install_decide(
        loop,
        [("goto", {"target": "stronghold"}), ("goto", {"target": {"x": 5, "y": 60, "z": 5}})],
    )
    await loop._decide_and_act("periodic")

    assert len(calls) == 2
    history = loop.state.action_history
    assert any(
        a.failure_code == FailureCode.TARGET_NOT_FOUND.value and a.skill == "goto"
        for a in history
        if a.failure_code is not None
    )
    assert len(loop._test_sent) == 1
    target = loop._test_sent[0].args.target
    assert (target.x, target.y, target.z) == (5, 60, 5)


@pytest.mark.asyncio
async def test_unknown_named_location_twice_falls_back_to_base(loop: AgentLoop):
    calls = _install_decide(loop, [("goto", {"target": "stronghold"})])
    await loop._decide_and_act("periodic")

    assert len(calls) == 2  # 再決定は1回だけ
    assert len(loop._test_sent) == 1
    target = loop._test_sent[0].args.target
    assert (target.x, target.y, target.z) == (BASE.x, BASE.y, BASE.z)
