"""Bridge configuration.

Loaded from (in increasing priority order):
    1. built-in defaults
    2. a TOML file (``bridge/config.toml`` by default, override with the
       ``MINECP_BRIDGE_CONFIG`` env var), read with the stdlib ``tomllib``
       (Python 3.11+, no extra dependency)
    3. environment variables prefixed with ``MINECP_BRIDGE_``

No settings library is used, since the allowed dependency list for this
package is limited to pydantic, websockets, httpx and jsonschema.
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field, fields
from pathlib import Path
from typing import Any

# bridge/src/minecp_bridge/config.py -> minecp_bridge -> src -> bridge -> repo root
_PACKAGE_DIR = Path(__file__).resolve().parent
_BRIDGE_DIR = _PACKAGE_DIR.parent.parent
_REPO_ROOT = _BRIDGE_DIR.parent


@dataclass(slots=True)
class BridgeConfig:
    # WebSocket server (bridge is server, Mod is client) — schema/README既定値
    ws_host: str = "127.0.0.1"
    ws_port: int = 8765

    # Ollama HTTP API
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen3:4b"
    ollama_timeout_s: float = 120.0

    # Filesystem layout
    schema_dir: Path = field(default_factory=lambda: _REPO_ROOT / "schema")
    state_dir: Path = field(default_factory=lambda: _BRIDGE_DIR / "state")
    logs_dir: Path = field(default_factory=lambda: _REPO_ROOT / "logs")

    # Decision loop timing (仕様書§3.2, §10)
    periodic_review_interval_s: float = 120.0
    stuck_timeout_s: float = 60.0

    # State / memory
    max_history: int = 50

    # LLM output validation (仕様書§4.2.4, §10)
    max_llm_retries: int = 3

    # Reflection loop (仕様書§4.2.5, §8.2)
    reflection_failure_threshold: int = 3

    # Death recovery (仕様書§8.3): vanilla item despawn timer is 5 minutes.
    item_despawn_s: float = 300.0

    @property
    def state_file(self) -> Path:
        return self.state_dir / "state.json"

    @property
    def ws_uri(self) -> str:
        return f"ws://{self.ws_host}:{self.ws_port}"


_ENV_PREFIX = "MINECP_BRIDGE_"

_TYPE_CASTERS = {
    str: str,
    int: int,
    float: float,
    Path: Path,
}


def _apply_overrides(cfg: BridgeConfig, overrides: dict[str, Any]) -> None:
    valid_fields = {f.name: f.type for f in fields(cfg)}
    for key, value in overrides.items():
        if key not in valid_fields:
            continue
        current = getattr(cfg, key)
        caster = type(current) if current is not None else str
        if caster in _TYPE_CASTERS and not isinstance(value, caster):
            value = _TYPE_CASTERS[caster](value)
        setattr(cfg, key, value)


def load_config(toml_path: Path | None = None, env: dict[str, str] | None = None) -> BridgeConfig:
    """Build a BridgeConfig from defaults + optional TOML file + env vars."""

    cfg = BridgeConfig()

    if toml_path is None:
        env_path = (env or os.environ).get(f"{_ENV_PREFIX}CONFIG")
        toml_path = Path(env_path) if env_path else _BRIDGE_DIR / "config.toml"

    if toml_path.exists():
        with toml_path.open("rb") as f:
            data = tomllib.load(f)
        _apply_overrides(cfg, data)

    env_vars = env if env is not None else os.environ
    env_overrides: dict[str, Any] = {}
    for key, raw_value in env_vars.items():
        if not key.startswith(_ENV_PREFIX):
            continue
        field_name = key[len(_ENV_PREFIX):].lower()
        if field_name == "config":
            continue
        env_overrides[field_name] = raw_value
    _apply_overrides(cfg, env_overrides)

    cfg.state_dir.mkdir(parents=True, exist_ok=True)
    cfg.logs_dir.mkdir(parents=True, exist_ok=True)

    return cfg
