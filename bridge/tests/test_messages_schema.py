"""Requirement (1): Pydantic models in messages.py agree with the JSON Schema
files in schema/ — sample messages must be valid under both.
"""

from __future__ import annotations

import pytest

from conftest import (
    sample_event_raw,
    sample_observation_raw,
    sample_skill_command_raw,
    sample_skill_result_raw,
)
from minecp_bridge.messages import (
    EventAdapter,
    Observation,
    SkillCommandAdapter,
    SkillResult,
    parse_incoming,
    to_wire,
)
from minecp_bridge.schema_validation import SchemaValidatorSet


@pytest.fixture(scope="module")
def validators(schema_dir):
    return SchemaValidatorSet(schema_dir)


def test_observation_round_trip_and_schema_valid(validators):
    raw = sample_observation_raw()
    validators.validate("observation", raw)  # raw wire dict is schema-valid

    model = Observation.model_validate(raw)
    wire = to_wire(model)
    validators.validate("observation", wire)  # re-serialized model is still schema-valid
    assert wire["self"]["hp"] == 20.0
    assert wire["current_skill"] is None  # required-but-nullable field survives round trip

    reparsed = parse_incoming(wire)
    assert isinstance(reparsed, Observation)
    assert reparsed.self_.pos.x == 10.5


def test_observation_rejects_ad_hoc_fields(validators):
    raw = sample_observation_raw()
    raw["not_in_schema"] = "nope"
    with pytest.raises(Exception):
        validators.validate("observation", raw)
    with pytest.raises(Exception):
        Observation.model_validate(raw)


@pytest.mark.parametrize(
    "skill,args",
    [
        ("goto", {"target": "base"}),
        ("goto", {"target": {"x": 1, "y": 2, "z": 3}}),
        ("mine", {"block": "minecraft:iron_ore", "count": 3}),
        ("craft", {"item": "minecraft:iron_pickaxe", "count": 1}),
        ("smelt", {"item": "minecraft:iron_ingot", "count": 4}),
        ("place", {"block": "minecraft:torch", "offset": {"x": 0, "y": 0, "z": 1}}),
        ("attack", {"target_type": "nearest_hostile"}),
        ("eat", {}),
        ("equip", {"item": "minecraft:iron_sword"}),
        ("use_portal", {"portal_type": "nether"}),
        ("build_portal", {}),
        ("throw_ender_eye", {}),
        ("fight_dragon", {}),
    ],
)
def test_all_skill_commands_round_trip_and_schema_valid(validators, skill, args):
    raw = sample_skill_command_raw()
    raw["skill"] = skill
    raw["args"] = args
    validators.validate("skill_command", raw)

    command = SkillCommandAdapter.validate_python(raw)
    wire = to_wire(command)
    validators.validate("skill_command", wire)

    reparsed = SkillCommandAdapter.validate_python(wire)
    assert reparsed.skill.value == skill


def test_skill_command_rejects_wrong_args_for_skill(validators):
    raw = sample_skill_command_raw()
    raw["skill"] = "mine"
    raw["args"] = {"target": "base"}  # goto's args shape, invalid for mine
    with pytest.raises(Exception):
        validators.validate("skill_command", raw)
    with pytest.raises(Exception):
        SkillCommandAdapter.validate_python(raw)


@pytest.mark.parametrize("status", ["success", "failure"])
def test_skill_result_round_trip_and_schema_valid(validators, status):
    raw = sample_skill_result_raw(status=status)
    validators.validate("skill_result", raw)

    model = SkillResult.model_validate(raw)
    wire = to_wire(model)
    validators.validate("skill_result", wire)
    if status == "success":
        assert "failure_code" not in wire
    else:
        assert wire["failure_code"] == "PATH_NOT_FOUND"

    reparsed = parse_incoming(wire)
    assert isinstance(reparsed, SkillResult)
    assert reparsed.status.value == status


def test_skill_result_failure_requires_failure_code(validators):
    raw = sample_skill_result_raw(status="failure")
    del raw["failure_code"]
    with pytest.raises(Exception):
        validators.validate("skill_result", raw)
    with pytest.raises(Exception):
        SkillResult.model_validate(raw)


@pytest.mark.parametrize(
    "event_type", ["death", "respawned", "attacked", "hp_critical", "item_pickup", "advancement"]
)
def test_all_events_round_trip_and_schema_valid(validators, event_type):
    raw = sample_event_raw(event_type=event_type)
    validators.validate("event", raw)

    model = EventAdapter.validate_python(raw)
    wire = to_wire(model)
    validators.validate("event", wire)

    reparsed = parse_incoming(wire)
    assert reparsed.event_type.value == event_type
