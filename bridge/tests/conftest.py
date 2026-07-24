from __future__ import annotations

from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_DIR = REPO_ROOT / "schema"


@pytest.fixture(scope="session")
def schema_dir() -> Path:
    assert SCHEMA_DIR.exists(), f"schema dir not found at {SCHEMA_DIR}"
    return SCHEMA_DIR


def sample_observation_raw(seq: int = 1, reason: str = "periodic") -> dict:
    return {
        "message_type": "observation",
        "timestamp_ms": 1_700_000_000_000,
        "seq": seq,
        "reason": reason,
        "self": {
            "hp": 20.0,
            "food": 20,
            "pos": {"x": 10.5, "y": 64.0, "z": -3.0},
            "yaw": 90.0,
            "pitch": 0.0,
            "dimension": "overworld",
            "game_time": 12000,
            "time_of_day": 1000,
        },
        "inventory": {
            "items": [
                {"id": "minecraft:oak_log", "count": 12},
                {"id": "minecraft:iron_pickaxe", "count": 1},
                {"id": "minecraft:iron_sword", "count": 1},
            ],
            "empty_slots": 30,
        },
        "equipment": {
            "main_hand": "minecraft:iron_pickaxe",
            "off_hand": None,
            "helmet": None,
            "chestplate": None,
            "leggings": None,
            "boots": None,
        },
        "nearby": {
            "points_of_interest": [
                {
                    "kind": "ore",
                    "id": "minecraft:iron_ore",
                    "pos": {"x": 12, "y": 60, "z": -2},
                    "distance": 5.2,
                }
            ],
            "hostiles": [
                {"type": "minecraft:zombie", "distance": 8.0, "pos": {"x": 15.0, "y": 64.0, "z": -3.0}}
            ],
            "villagers": 0,
        },
        "progress": {
            "blaze_rods": 0,
            "ender_pearls": 0,
            "ender_eyes": 0,
            "advancements": ["minecraft:story/mine_stone"],
        },
        "current_skill": None,
    }


def sample_skill_command_raw(seq: int = 1) -> dict:
    return {
        "message_type": "skill_command",
        "timestamp_ms": 1_700_000_000_000,
        "seq": seq,
        "command_id": "11111111-1111-1111-1111-111111111111",
        "skill": "goto",
        "args": {"target": "base"},
    }


def sample_skill_result_raw(seq: int = 1, status: str = "success") -> dict:
    base = {
        "message_type": "skill_result",
        "timestamp_ms": 1_700_000_000_000,
        "seq": seq,
        "command_id": "11111111-1111-1111-1111-111111111111",
        "status": status,
        "detail": "arrived",
    }
    if status == "failure":
        base["failure_code"] = "PATH_NOT_FOUND"
    return base


def sample_event_raw(event_type: str = "death", seq: int = 1) -> dict:
    data_by_type = {
        "death": {"pos": {"x": 1.0, "y": 60.0, "z": 2.0}, "dimension": "overworld", "cause": "minecraft:zombie"},
        "respawned": {"pos": {"x": 0.0, "y": 64.0, "z": 0.0}, "dimension": "overworld"},
        "attacked": {"attacker_type": "minecraft:zombie", "hp": 8.0},
        "hp_critical": {"hp": 4.0},
        "item_pickup": {"item": {"id": "minecraft:blaze_rod", "count": 1}},
        "advancement": {"id": "minecraft:story/enter_the_nether"},
    }
    return {
        "message_type": "event",
        "timestamp_ms": 1_700_000_000_000,
        "seq": seq,
        "event_type": event_type,
        "data": data_by_type[event_type],
    }
