"""Pydantic models mirroring the JSON Schema definitions in ``schema/``.

This module is intentionally a 1:1 mirror of the schema files. Field names,
enum values, and nesting match the schema exactly (仕様書§7.2: schema/ is the
single source of truth). Do not add ad-hoc fields that do not appear in
``schema/`` — if a schema needs a new field, update ``schema/`` first.

Covered:
    - common.schema.json  -> Vec3, BlockPos, Dimension, ItemStack,
      NamedLocation, Milestone, MessageEnvelope
    - observation.schema.json -> Observation
    - skill_command.schema.json -> SkillCommand (discriminated union, one
      concrete model per skill)
    - skill_result.schema.json -> SkillResult
    - event.schema.json -> Event (discriminated union, one concrete model
      per event_type)
    - failure_codes.schema.json -> FailureCode
"""

from __future__ import annotations

import uuid
from enum import Enum
from typing import Annotated, Any, Literal, Union

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator

# ---------------------------------------------------------------------------
# common.schema.json
# ---------------------------------------------------------------------------


class MessageType(str, Enum):
    observation = "observation"
    skill_command = "skill_command"
    skill_result = "skill_result"
    event = "event"


class Dimension(str, Enum):
    overworld = "overworld"
    nether = "nether"
    end = "end"


class NamedLocation(str, Enum):
    base = "base"
    nether_portal_overworld = "nether_portal_overworld"
    nether_portal_nether = "nether_portal_nether"
    stronghold = "stronghold"
    last_death = "last_death"


class Milestone(str, Enum):
    """The 14-node milestone DAG (仕様書§8.1)."""

    wood = "wood"
    wooden_tools = "wooden_tools"
    stone_tools = "stone_tools"
    iron_gear = "iron_gear"
    food_secured = "food_secured"
    diamond_tools = "diamond_tools"
    nether_portal = "nether_portal"
    blaze_rods = "blaze_rods"
    ender_pearls = "ender_pearls"
    ender_eyes = "ender_eyes"
    stronghold_found = "stronghold_found"
    portal_activated = "portal_activated"
    gear_final_check = "gear_final_check"
    dragon_slain = "dragon_slain"


class Vec3(BaseModel):
    model_config = ConfigDict(extra="forbid")

    x: float
    y: float
    z: float


class BlockPos(BaseModel):
    model_config = ConfigDict(extra="forbid")

    x: int
    y: int
    z: int


class ItemStack(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    count: int = Field(ge=1)


class MessageEnvelope(BaseModel):
    """Common fields present on every message (仕様書§7.2)."""

    model_config = ConfigDict(extra="forbid")

    message_type: MessageType
    timestamp_ms: int
    seq: int = Field(ge=0)


def now_ms() -> int:
    import time

    return int(time.time() * 1000)


def new_command_id() -> str:
    return str(uuid.uuid4())


# ---------------------------------------------------------------------------
# failure_codes.schema.json
# ---------------------------------------------------------------------------


class FailureCode(str, Enum):
    INSUFFICIENT_MATERIALS = "INSUFFICIENT_MATERIALS"
    NO_FUEL = "NO_FUEL"
    NO_TOOL = "NO_TOOL"
    PATH_NOT_FOUND = "PATH_NOT_FOUND"
    TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
    TARGET_UNREACHABLE = "TARGET_UNREACHABLE"
    INVENTORY_FULL = "INVENTORY_FULL"
    AGENT_DIED = "AGENT_DIED"
    TIMEOUT_STUCK = "TIMEOUT_STUCK"
    INTERRUPTED_BY_NEW_COMMAND = "INTERRUPTED_BY_NEW_COMMAND"
    INTERRUPTED_BY_DISCONNECT = "INTERRUPTED_BY_DISCONNECT"
    INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    UNSUPPORTED_ITEM = "UNSUPPORTED_ITEM"
    CRAFTING_FAILED = "CRAFTING_FAILED"
    SMELTING_FAILED = "SMELTING_FAILED"
    PLACEMENT_OBSTRUCTED = "PLACEMENT_OBSTRUCTED"
    NO_FOOD = "NO_FOOD"
    FLED_FROM_COMBAT = "FLED_FROM_COMBAT"
    PORTAL_NOT_FOUND = "PORTAL_NOT_FOUND"
    PORTAL_BUILD_FAILED = "PORTAL_BUILD_FAILED"
    NO_ENDER_EYE = "NO_ENDER_EYE"
    DRAGON_FIGHT_ABORTED = "DRAGON_FIGHT_ABORTED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


# ---------------------------------------------------------------------------
# observation.schema.json
# ---------------------------------------------------------------------------


class ObservationReason(str, Enum):
    skill_finished = "skill_finished"
    periodic = "periodic"
    interrupt = "interrupt"
    reconnected = "reconnected"


class SelfStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")

    hp: float = Field(ge=0, le=20)
    food: int = Field(ge=0, le=20)
    pos: Vec3
    yaw: float
    pitch: float
    dimension: Dimension
    game_time: int
    time_of_day: int = Field(ge=0, le=23999)


class InventoryState(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[ItemStack]
    empty_slots: int = Field(ge=0)


class Equipment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    main_hand: str | None
    off_hand: str | None
    helmet: str | None
    chestplate: str | None
    leggings: str | None
    boots: str | None


class PointOfInterestKind(str, Enum):
    ore = "ore"
    lava = "lava"
    water = "water"
    nether_portal = "nether_portal"
    end_portal_frame = "end_portal_frame"
    stronghold_block = "stronghold_block"
    chest = "chest"
    crafting_table = "crafting_table"
    furnace = "furnace"
    bed = "bed"


class PointOfInterest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    kind: PointOfInterestKind
    id: str
    pos: BlockPos
    distance: float = Field(ge=0)


class Hostile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: str
    distance: float = Field(ge=0)
    pos: Vec3


class Nearby(BaseModel):
    model_config = ConfigDict(extra="forbid")

    points_of_interest: list[PointOfInterest]
    hostiles: list[Hostile]
    villagers: int = Field(ge=0)


class Progress(BaseModel):
    model_config = ConfigDict(extra="forbid")

    blaze_rods: int = Field(ge=0)
    ender_pearls: int = Field(ge=0)
    ender_eyes: int = Field(ge=0)
    advancements: list[str]


class CurrentSkillInfo(BaseModel):
    model_config = ConfigDict(extra="forbid")

    command_id: str
    skill: str
    elapsed_ms: int = Field(ge=0)


class Observation(MessageEnvelope):
    """Mod -> Bridge. Memory coordinates are intentionally absent (ADR-0001)."""

    message_type: Literal[MessageType.observation] = MessageType.observation
    reason: ObservationReason
    self_: SelfStatus = Field(alias="self")
    inventory: InventoryState
    equipment: Equipment
    nearby: Nearby
    progress: Progress
    current_skill: CurrentSkillInfo | None

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


# ---------------------------------------------------------------------------
# skill_command.schema.json
# ---------------------------------------------------------------------------


class SkillName(str, Enum):
    goto = "goto"
    mine = "mine"
    craft = "craft"
    smelt = "smelt"
    place = "place"
    attack = "attack"
    eat = "eat"
    equip = "equip"
    use_portal = "use_portal"
    build_portal = "build_portal"
    throw_ender_eye = "throw_ender_eye"
    fight_dragon = "fight_dragon"


class GotoArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    target: BlockPos | NamedLocation


class MineArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    block: str
    count: int = Field(ge=1)


class CraftArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    item: str
    count: int = Field(ge=1)


class SmeltArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    item: str
    count: int = Field(ge=1)


class PlaceArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    block: str
    offset: BlockPos


class AttackArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    target_type: str


class EatArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EquipArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    item: str


class UsePortalArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    portal_type: Literal["nether", "end"]


class BuildPortalArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ThrowEnderEyeArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")


class FightDragonArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")


class _SkillCommandBase(MessageEnvelope):
    message_type: Literal[MessageType.skill_command] = MessageType.skill_command
    command_id: str


class GotoCommand(_SkillCommandBase):
    skill: Literal[SkillName.goto] = SkillName.goto
    args: GotoArgs


class MineCommand(_SkillCommandBase):
    skill: Literal[SkillName.mine] = SkillName.mine
    args: MineArgs


class CraftCommand(_SkillCommandBase):
    skill: Literal[SkillName.craft] = SkillName.craft
    args: CraftArgs


class SmeltCommand(_SkillCommandBase):
    skill: Literal[SkillName.smelt] = SkillName.smelt
    args: SmeltArgs


class PlaceCommand(_SkillCommandBase):
    skill: Literal[SkillName.place] = SkillName.place
    args: PlaceArgs


class AttackCommand(_SkillCommandBase):
    skill: Literal[SkillName.attack] = SkillName.attack
    args: AttackArgs


class EatCommand(_SkillCommandBase):
    skill: Literal[SkillName.eat] = SkillName.eat
    args: EatArgs = Field(default_factory=EatArgs)


class EquipCommand(_SkillCommandBase):
    skill: Literal[SkillName.equip] = SkillName.equip
    args: EquipArgs


class UsePortalCommand(_SkillCommandBase):
    skill: Literal[SkillName.use_portal] = SkillName.use_portal
    args: UsePortalArgs


class BuildPortalCommand(_SkillCommandBase):
    skill: Literal[SkillName.build_portal] = SkillName.build_portal
    args: BuildPortalArgs = Field(default_factory=BuildPortalArgs)


class ThrowEnderEyeCommand(_SkillCommandBase):
    skill: Literal[SkillName.throw_ender_eye] = SkillName.throw_ender_eye
    args: ThrowEnderEyeArgs = Field(default_factory=ThrowEnderEyeArgs)


class FightDragonCommand(_SkillCommandBase):
    skill: Literal[SkillName.fight_dragon] = SkillName.fight_dragon
    args: FightDragonArgs = Field(default_factory=FightDragonArgs)


SkillCommand = Annotated[
    Union[
        GotoCommand,
        MineCommand,
        CraftCommand,
        SmeltCommand,
        PlaceCommand,
        AttackCommand,
        EatCommand,
        EquipCommand,
        UsePortalCommand,
        BuildPortalCommand,
        ThrowEnderEyeCommand,
        FightDragonCommand,
    ],
    Field(discriminator="skill"),
]

SkillCommandAdapter: TypeAdapter[SkillCommand] = TypeAdapter(SkillCommand)

SKILL_COMMAND_CLASSES: dict[SkillName, type[_SkillCommandBase]] = {
    SkillName.goto: GotoCommand,
    SkillName.mine: MineCommand,
    SkillName.craft: CraftCommand,
    SkillName.smelt: SmeltCommand,
    SkillName.place: PlaceCommand,
    SkillName.attack: AttackCommand,
    SkillName.eat: EatCommand,
    SkillName.equip: EquipCommand,
    SkillName.use_portal: UsePortalCommand,
    SkillName.build_portal: BuildPortalCommand,
    SkillName.throw_ender_eye: ThrowEnderEyeCommand,
    SkillName.fight_dragon: FightDragonCommand,
}

SKILL_ARGS_CLASSES: dict[SkillName, type[BaseModel]] = {
    SkillName.goto: GotoArgs,
    SkillName.mine: MineArgs,
    SkillName.craft: CraftArgs,
    SkillName.smelt: SmeltArgs,
    SkillName.place: PlaceArgs,
    SkillName.attack: AttackArgs,
    SkillName.eat: EatArgs,
    SkillName.equip: EquipArgs,
    SkillName.use_portal: UsePortalArgs,
    SkillName.build_portal: BuildPortalArgs,
    SkillName.throw_ender_eye: ThrowEnderEyeArgs,
    SkillName.fight_dragon: FightDragonArgs,
}


def build_skill_command(
    skill: SkillName | str,
    args: dict[str, Any],
    *,
    seq: int,
    command_id: str | None = None,
    timestamp_ms: int | None = None,
) -> _SkillCommandBase:
    """Construct and validate a concrete SkillCommand model from raw args."""

    skill_enum = SkillName(skill)
    cls = SKILL_COMMAND_CLASSES[skill_enum]
    return cls(
        timestamp_ms=timestamp_ms if timestamp_ms is not None else now_ms(),
        seq=seq,
        command_id=command_id or new_command_id(),
        skill=skill_enum,
        args=args,
    )


# ---------------------------------------------------------------------------
# skill_result.schema.json
# ---------------------------------------------------------------------------


class SkillStatus(str, Enum):
    success = "success"
    failure = "failure"


class SkillResult(MessageEnvelope):
    message_type: Literal[MessageType.skill_result] = MessageType.skill_result
    command_id: str
    status: SkillStatus
    failure_code: FailureCode | None = None
    detail: str | None = None
    data: dict[str, Any] | None = None

    @model_validator(mode="after")
    def _failure_code_required_on_failure(self) -> "SkillResult":
        if self.status == SkillStatus.failure and self.failure_code is None:
            raise ValueError("failure_code is required when status='failure'")
        return self


# ---------------------------------------------------------------------------
# event.schema.json
# ---------------------------------------------------------------------------


class EventType(str, Enum):
    death = "death"
    respawned = "respawned"
    attacked = "attacked"
    hp_critical = "hp_critical"
    item_pickup = "item_pickup"
    advancement = "advancement"


class DeathData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    pos: Vec3
    dimension: Dimension
    cause: str


class RespawnedData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    pos: Vec3
    dimension: Dimension


class AttackedData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    attacker_type: str
    hp: float


class HpCriticalData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    hp: float


class ItemPickupData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    item: ItemStack


class AdvancementData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str


class _EventBase(MessageEnvelope):
    message_type: Literal[MessageType.event] = MessageType.event


class DeathEvent(_EventBase):
    event_type: Literal[EventType.death] = EventType.death
    data: DeathData


class RespawnedEvent(_EventBase):
    event_type: Literal[EventType.respawned] = EventType.respawned
    data: RespawnedData


class AttackedEvent(_EventBase):
    event_type: Literal[EventType.attacked] = EventType.attacked
    data: AttackedData


class HpCriticalEvent(_EventBase):
    event_type: Literal[EventType.hp_critical] = EventType.hp_critical
    data: HpCriticalData


class ItemPickupEvent(_EventBase):
    event_type: Literal[EventType.item_pickup] = EventType.item_pickup
    data: ItemPickupData


class AdvancementEvent(_EventBase):
    event_type: Literal[EventType.advancement] = EventType.advancement
    data: AdvancementData


Event = Annotated[
    Union[
        DeathEvent,
        RespawnedEvent,
        AttackedEvent,
        HpCriticalEvent,
        ItemPickupEvent,
        AdvancementEvent,
    ],
    Field(discriminator="event_type"),
]

EventAdapter: TypeAdapter[Event] = TypeAdapter(Event)


# ---------------------------------------------------------------------------
# Wire (de)serialization helpers
# ---------------------------------------------------------------------------

IncomingMessage = Union[Observation, SkillResult, DeathEvent, RespawnedEvent, AttackedEvent, HpCriticalEvent, ItemPickupEvent, AdvancementEvent]


def to_wire(message: BaseModel) -> dict[str, Any]:
    """Serialize a message model to the flat wire dict (schema/README ワイヤ形式).

    Uses ``exclude_unset`` so that fields which are optional-and-absent in the
    schema (e.g. ``SkillResult.failure_code`` on success) are omitted rather
    than emitted as ``null``, while fields that are required-but-nullable
    (e.g. ``Equipment.main_hand``) stay present because callers always set
    them explicitly.
    """

    data = message.model_dump(mode="json", by_alias=True, exclude_unset=True)
    # 判別フィールド(message_type / skill / event_type)はLiteralデフォルトのため
    # exclude_unsetで落ちうるが、ワイヤ上は必須なので常に含める。
    for field_name in ("message_type", "skill", "event_type"):
        if field_name in type(message).model_fields and field_name not in data:
            value = getattr(message, field_name)
            data[field_name] = value.value if isinstance(value, Enum) else value
    return data


def parse_incoming(raw: dict[str, Any]) -> IncomingMessage:
    """Parse a raw wire dict (already schema-validated) into its Pydantic model.

    Only message types the bridge receives from the Mod are handled here:
    observation, skill_result, event. ``skill_command`` is bridge -> Mod only.
    """

    message_type = raw.get("message_type")
    if message_type == MessageType.observation.value:
        return Observation.model_validate(raw)
    if message_type == MessageType.skill_result.value:
        return SkillResult.model_validate(raw)
    if message_type == MessageType.event.value:
        return EventAdapter.validate_python(raw)
    raise ValueError(f"Unexpected or unsupported message_type for incoming message: {message_type!r}")
