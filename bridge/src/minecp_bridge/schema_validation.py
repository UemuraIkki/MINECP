"""JSON Schema validation against the single source of truth in ``schema/``.

The bridge validates every inbound Mod message against the authoritative
JSON Schema files (not just the mirrored Pydantic models) before dispatching
it, per 仕様書§4.2.4 / §7.2. Pydantic parsing happens *after* schema
validation succeeds.
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012


class SchemaValidationError(ValueError):
    """Raised when a raw message fails JSON Schema validation."""


def _load_registry(schema_dir: Path) -> Registry:
    resources: list[tuple[str, Resource]] = []
    for path in sorted(schema_dir.glob("*.schema.json")):
        contents = json.loads(path.read_text(encoding="utf-8"))
        resource = Resource.from_contents(contents, default_specification=DRAFT202012)
        resources.append((path.name, resource))
    return Registry().with_resources(resources)


class SchemaValidatorSet:
    """Loads schema/*.json once and exposes a validator per message type."""

    def __init__(self, schema_dir: Path):
        self.schema_dir = schema_dir
        self._registry = _load_registry(schema_dir)
        self._schemas: dict[str, dict[str, Any]] = {}
        self._validators: dict[str, Draft202012Validator] = {}
        for name in (
            "observation.schema.json",
            "skill_command.schema.json",
            "skill_result.schema.json",
            "event.schema.json",
            "failure_codes.schema.json",
        ):
            path = schema_dir / name
            schema = json.loads(path.read_text(encoding="utf-8"))
            self._schemas[name] = schema
            self._validators[name] = Draft202012Validator(schema, registry=self._registry)

    def validate(self, message_type: str, raw: dict[str, Any]) -> None:
        """Validate a raw wire dict against the schema for its message type.

        Raises SchemaValidationError with a readable message on failure.
        """

        schema_name = {
            "observation": "observation.schema.json",
            "skill_command": "skill_command.schema.json",
            "skill_result": "skill_result.schema.json",
            "event": "event.schema.json",
        }.get(message_type)
        if schema_name is None:
            raise SchemaValidationError(f"Unknown message_type: {message_type!r}")
        validator = self._validators[schema_name]
        errors = sorted(validator.iter_errors(raw), key=lambda e: list(e.path))
        if errors:
            details = "; ".join(f"{list(e.path)}: {e.message}" for e in errors)
            raise SchemaValidationError(f"Schema validation failed for {message_type}: {details}")

    def validate_by_envelope(self, raw: dict[str, Any]) -> None:
        """Validate using the ``message_type`` field found inside ``raw``."""

        message_type = raw.get("message_type")
        if not isinstance(message_type, str):
            raise SchemaValidationError("Missing or invalid 'message_type' field")
        self.validate(message_type, raw)


@lru_cache(maxsize=8)
def get_validator_set(schema_dir: str) -> SchemaValidatorSet:
    return SchemaValidatorSet(Path(schema_dir))
