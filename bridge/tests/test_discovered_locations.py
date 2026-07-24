"""build_portal / throw_ender_eyeにはMod側から座標が渡されない(ADR-0001:
位置オラクル禁止)ため、ブリッジは`nearby.points_of_interest`で実際に
nether_portal/stronghold_blockを観測した時点で記憶座標を登録する。
このモジュールはその配線(`AgentLoop._register_discovered_locations`)を検証する。
"""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest
from conftest import sample_observation_raw

from minecp_bridge.agent_loop import AgentLoop
from minecp_bridge.config import BridgeConfig
from minecp_bridge.logging_setup import get_session_logger
from minecp_bridge.messages import NamedLocation, Observation
from minecp_bridge.state import BridgeState


@pytest.fixture
async def loop(tmp_path: Path):
    config = BridgeConfig(state_dir=tmp_path, logs_dir=tmp_path)
    session_logger = get_session_logger(tmp_path)
    async with httpx.AsyncClient() as client:
        yield AgentLoop(config, BridgeState(), client, session_logger)


def _observation_with_poi(poi: dict, *, dimension: str = "overworld") -> Observation:
    raw = sample_observation_raw()
    raw["self"]["dimension"] = dimension
    raw["nearby"]["points_of_interest"] = [poi]
    return Observation.model_validate(raw)


@pytest.mark.asyncio
async def test_nether_portal_poi_registers_overworld_location(loop: AgentLoop):
    poi = {"kind": "nether_portal", "id": "minecraft:nether_portal", "pos": {"x": 7, "y": 65, "z": -9}, "distance": 3.0}
    await loop.on_observation(_observation_with_poi(poi, dimension="overworld"))

    pos = loop.state.get_memory(NamedLocation.nether_portal_overworld)
    assert pos is not None
    assert (pos.x, pos.y, pos.z) == (7, 65, -9)
    assert loop._milestone_context.has_nether_portal is True


@pytest.mark.asyncio
async def test_nether_portal_poi_registers_nether_side_location(loop: AgentLoop):
    poi = {"kind": "nether_portal", "id": "minecraft:nether_portal", "pos": {"x": 1, "y": 70, "z": 2}, "distance": 3.0}
    await loop.on_observation(_observation_with_poi(poi, dimension="nether"))

    assert loop.state.get_memory(NamedLocation.nether_portal_nether) is not None
    assert loop.state.get_memory(NamedLocation.nether_portal_overworld) is None
    # The nether-side sighting alone isn't evidence of the overworld link.
    assert loop._milestone_context.has_nether_portal is False


@pytest.mark.asyncio
async def test_stronghold_block_poi_registers_location(loop: AgentLoop):
    poi = {"kind": "stronghold_block", "id": "minecraft:stone_bricks", "pos": {"x": -30, "y": 40, "z": 12}, "distance": 4.0}
    await loop.on_observation(_observation_with_poi(poi))

    pos = loop.state.get_memory(NamedLocation.stronghold)
    assert pos is not None
    assert (pos.x, pos.y, pos.z) == (-30, 40, 12)
    assert loop._milestone_context.has_stronghold_location is True


@pytest.mark.asyncio
async def test_first_sighting_is_not_overwritten(loop: AgentLoop):
    first = {"kind": "stronghold_block", "id": "minecraft:stone_bricks", "pos": {"x": 1, "y": 1, "z": 1}, "distance": 1.0}
    second = {"kind": "stronghold_block", "id": "minecraft:stone_bricks", "pos": {"x": 99, "y": 1, "z": 99}, "distance": 1.0}
    await loop.on_observation(_observation_with_poi(first))
    await loop.on_observation(_observation_with_poi(second))

    pos = loop.state.get_memory(NamedLocation.stronghold)
    assert (pos.x, pos.y, pos.z) == (1, 1, 1)
