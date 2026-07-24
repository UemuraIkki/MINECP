"""Sanity coverage for the milestone DAG and completion heuristics."""

from __future__ import annotations

from conftest import sample_observation_raw
from minecp_bridge.messages import Milestone, Observation
from minecp_bridge.milestones import (
    MILESTONE_ORDER,
    MilestoneContext,
    current_milestone,
    evaluate_all,
    is_completed,
    milestone_dag_text,
)


def test_milestone_order_matches_schema_enum_and_spec_length():
    assert len(MILESTONE_ORDER) == 14
    assert MILESTONE_ORDER[0] is Milestone.wood
    assert MILESTONE_ORDER[-1] is Milestone.dragon_slain


def test_milestone_dag_text_is_ordered_chain():
    text = milestone_dag_text()
    assert text.startswith("wood -> wooden_tools")
    assert text.endswith("dragon_slain")


def test_wood_and_wooden_tools_from_inventory():
    raw = sample_observation_raw()
    raw["inventory"]["items"] = [{"id": "minecraft:wooden_pickaxe", "count": 1}]
    obs = Observation.model_validate(raw)
    completed = evaluate_all(obs)
    assert Milestone.wood in completed
    assert Milestone.wooden_tools in completed
    assert Milestone.stone_tools not in completed


def test_iron_gear_requires_pickaxe_and_sword():
    raw = sample_observation_raw()
    raw["inventory"]["items"] = [{"id": "minecraft:iron_pickaxe", "count": 1}]
    obs = Observation.model_validate(raw)
    assert not is_completed(Milestone.iron_gear, obs)

    raw["inventory"]["items"].append({"id": "minecraft:iron_sword", "count": 1})
    obs = Observation.model_validate(raw)
    assert is_completed(Milestone.iron_gear, obs)


def test_blaze_rods_threshold_from_progress_counter():
    raw = sample_observation_raw()
    raw["progress"]["blaze_rods"] = 6
    obs = Observation.model_validate(raw)
    assert not is_completed(Milestone.blaze_rods, obs)

    raw["progress"]["blaze_rods"] = 7
    obs = Observation.model_validate(raw)
    assert is_completed(Milestone.blaze_rods, obs)


def test_nether_portal_requires_external_context():
    raw = sample_observation_raw()
    obs = Observation.model_validate(raw)
    assert not is_completed(Milestone.nether_portal, obs)
    assert is_completed(Milestone.nether_portal, obs, MilestoneContext(has_nether_portal=True))


def _with_equipment(raw: dict, *, sword: str | None, **armor: str | None) -> dict:
    if sword is not None:
        raw["inventory"]["items"].append({"id": sword, "count": 1})
    raw["equipment"].update(armor)
    return raw


def test_gear_final_check_rejects_non_diamond_armor():
    raw = sample_observation_raw()
    raw = _with_equipment(
        raw,
        sword="minecraft:diamond_sword",
        helmet="minecraft:leather_helmet",
        chestplate="minecraft:diamond_chestplate",
        leggings="minecraft:diamond_leggings",
        boots="minecraft:diamond_boots",
    )
    obs = Observation.model_validate(raw)
    assert not is_completed(Milestone.gear_final_check, obs)


def test_gear_final_check_rejects_missing_armor_piece():
    raw = sample_observation_raw()
    raw = _with_equipment(
        raw,
        sword="minecraft:diamond_sword",
        helmet="minecraft:diamond_helmet",
        chestplate="minecraft:diamond_chestplate",
        leggings="minecraft:diamond_leggings",
        boots=None,
    )
    obs = Observation.model_validate(raw)
    assert not is_completed(Milestone.gear_final_check, obs)


def test_gear_final_check_accepts_full_diamond_kit():
    raw = sample_observation_raw()
    raw = _with_equipment(
        raw,
        sword="minecraft:diamond_sword",
        helmet="minecraft:diamond_helmet",
        chestplate="minecraft:diamond_chestplate",
        leggings="minecraft:diamond_leggings",
        boots="minecraft:diamond_boots",
    )
    obs = Observation.model_validate(raw)
    assert is_completed(Milestone.gear_final_check, obs)


def test_gear_final_check_accepts_netherite_upgrade():
    raw = sample_observation_raw()
    raw = _with_equipment(
        raw,
        sword="minecraft:netherite_sword",
        helmet="minecraft:netherite_helmet",
        chestplate="minecraft:diamond_chestplate",
        leggings="minecraft:netherite_leggings",
        boots="minecraft:diamond_boots",
    )
    obs = Observation.model_validate(raw)
    assert is_completed(Milestone.gear_final_check, obs)


def test_current_milestone_is_first_incomplete_in_order():
    completed = {Milestone.wood, Milestone.wooden_tools}
    assert current_milestone(completed) is Milestone.stone_tools

    all_done = set(MILESTONE_ORDER)
    assert current_milestone(all_done) is Milestone.dragon_slain
