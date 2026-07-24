"""config.py had no dedicated test file; only its dataclass defaults were
used (via BridgeConfig(...) fixtures) without ever exercising load_config's
actual job: merging defaults < config.toml < MINECP_BRIDGE_* env vars, with
type casting and validation of unknown keys.
"""

from __future__ import annotations

from pathlib import Path

from minecp_bridge.config import BridgeConfig, load_config


def test_defaults_when_no_toml_and_no_env(tmp_path: Path):
    cfg = load_config(toml_path=tmp_path / "missing.toml", env={})

    assert cfg.ws_port == 8765
    assert cfg.ollama_model == "qwen3:4b"
    assert cfg.max_llm_retries == 3


def test_toml_overrides_defaults(tmp_path: Path):
    toml_path = tmp_path / "config.toml"
    toml_path.write_text('ws_port = 9999\nollama_model = "custom:1b"\n', encoding="utf-8")

    cfg = load_config(toml_path=toml_path, env={})

    assert cfg.ws_port == 9999
    assert cfg.ollama_model == "custom:1b"


def test_env_var_overrides_toml(tmp_path: Path):
    toml_path = tmp_path / "config.toml"
    toml_path.write_text("ws_port = 9999\n", encoding="utf-8")

    cfg = load_config(toml_path=toml_path, env={"MINECP_BRIDGE_WS_PORT": "1234"})

    assert cfg.ws_port == 1234  # env wins over toml


def test_env_var_casts_to_the_fields_declared_type(tmp_path: Path):
    cfg = load_config(
        toml_path=tmp_path / "missing.toml",
        env={"MINECP_BRIDGE_STUCK_TIMEOUT_S": "45.5", "MINECP_BRIDGE_MAX_LLM_RETRIES": "5"},
    )

    assert cfg.stuck_timeout_s == 45.5
    assert isinstance(cfg.stuck_timeout_s, float)
    assert cfg.max_llm_retries == 5
    assert isinstance(cfg.max_llm_retries, int)


def test_unknown_keys_are_ignored_not_crashed_on(tmp_path: Path):
    # BridgeConfig is a slots dataclass; setting an unknown attribute would
    # raise AttributeError if the filter in _apply_overrides were missing.
    toml_path = tmp_path / "config.toml"
    toml_path.write_text('unknown_field = "x"\nws_port = 1111\n', encoding="utf-8")

    cfg = load_config(toml_path=toml_path, env={"MINECP_BRIDGE_UNKNOWN_ENV_KEY": "y"})

    assert cfg.ws_port == 1111
    assert not hasattr(cfg, "unknown_field")


def test_minecp_bridge_config_env_var_redirects_toml_path(tmp_path: Path):
    custom_toml = tmp_path / "custom.toml"
    custom_toml.write_text("ws_port = 4242\n", encoding="utf-8")

    cfg = load_config(toml_path=None, env={"MINECP_BRIDGE_CONFIG": str(custom_toml)})

    assert cfg.ws_port == 4242


def test_load_config_creates_state_and_logs_directories(tmp_path: Path):
    state_dir = tmp_path / "nested" / "state"
    logs_dir = tmp_path / "nested" / "logs"
    toml_path = tmp_path / "config.toml"
    toml_path.write_text(f'state_dir = "{state_dir.as_posix()}"\nlogs_dir = "{logs_dir.as_posix()}"\n', encoding="utf-8")

    cfg = load_config(toml_path=toml_path, env={})

    assert cfg.state_dir == state_dir
    assert state_dir.is_dir()
    assert logs_dir.is_dir()


def test_ws_uri_and_state_file_properties():
    cfg = BridgeConfig(ws_host="10.0.0.1", ws_port=9000)
    assert cfg.ws_uri == "ws://10.0.0.1:9000"
    assert cfg.state_file == cfg.state_dir / "state.json"
