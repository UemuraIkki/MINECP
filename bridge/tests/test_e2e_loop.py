"""E2E疎通テスト: モックMod(実WebSocketクライアント) ⇄ ブリッジ ⇄ モックOllama。

Minecraft・Ollama実機なしで意思決定ループ一周を検証する:
observation受信 → LLM(モック)決定 → skill_command送信(スキーマ妥当) →
skill_result + 次observation → 次のskill_command。
"""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path

import httpx
import pytest
import websockets

from minecp_bridge.agent_loop import AgentLoop
from minecp_bridge.config import BridgeConfig
from minecp_bridge.logging_setup import get_session_logger
from minecp_bridge.schema_validation import get_validator_set
from minecp_bridge.state import BridgeState

RECV_TIMEOUT_S = 5.0


def _now_ms() -> int:
    return int(time.time() * 1000)


def _observation(seq: int, reason: str) -> dict:
    return {
        "message_type": "observation",
        "timestamp_ms": _now_ms(),
        "seq": seq,
        "reason": reason,
        "self": {
            "hp": 20.0,
            "food": 20,
            "pos": {"x": 0.5, "y": 64.0, "z": 0.5},
            "yaw": 0.0,
            "pitch": 0.0,
            "dimension": "overworld",
            "game_time": 100,
            "time_of_day": 1000,
        },
        "inventory": {"items": [], "empty_slots": 36},
        "equipment": {
            "main_hand": None,
            "off_hand": None,
            "helmet": None,
            "chestplate": None,
            "leggings": None,
            "boots": None,
        },
        "nearby": {"points_of_interest": [], "hostiles": [], "villagers": 0},
        "progress": {"blaze_rods": 0, "ender_pearls": 0, "ender_eyes": 0, "advancements": []},
        "current_skill": None,
    }


def _skill_result(seq: int, command_id: str) -> dict:
    return {
        "message_type": "skill_result",
        "timestamp_ms": _now_ms(),
        "seq": seq,
        "command_id": command_id,
        "status": "success",
        "detail": "done",
        "data": {"mined_count": 4},
    }


def _ollama_transport(tool_calls_sequence: list[dict]) -> httpx.MockTransport:
    """/api/chat への各呼び出しに、tool_calls_sequenceを順に返すモックOllama。"""

    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/chat"
        idx = min(calls["n"], len(tool_calls_sequence) - 1)
        calls["n"] += 1
        call = tool_calls_sequence[idx]
        return httpx.Response(
            200,
            json={"message": {"role": "assistant", "tool_calls": [{"function": call}]}},
        )

    return httpx.MockTransport(handler)


@pytest.mark.asyncio
async def test_full_decision_loop_over_real_websocket(tmp_path: Path):
    config = BridgeConfig(
        ws_port=0,  # OSに空きポートを割り当てさせる
        state_dir=tmp_path / "state",
        logs_dir=tmp_path / "logs",
        periodic_review_interval_s=3600.0,  # このテストでは定期見直しを事実上無効化
    )
    config.state_dir.mkdir(parents=True)
    config.logs_dir.mkdir(parents=True)

    transport = _ollama_transport(
        [
            {"name": "mine", "arguments": {"block": "log", "count": 4}},
            {"name": "craft", "arguments": {"item": "minecraft:crafting_table", "count": 1}},
        ]
    )
    validators = get_validator_set(str(config.schema_dir))

    async with httpx.AsyncClient(transport=transport) as client:
        loop = AgentLoop(config, BridgeState(), client, get_session_logger(config.logs_dir))
        await loop.start()
        try:
            port = loop.server._server.sockets[0].getsockname()[1]

            async with websockets.connect(f"ws://127.0.0.1:{port}") as fake_mod:
                # 1. 初回observation(reconnected)→ 意思決定 → skill_command受信
                await fake_mod.send(json.dumps(_observation(seq=0, reason="reconnected")))
                raw1 = json.loads(await asyncio.wait_for(fake_mod.recv(), RECV_TIMEOUT_S))

                validators.validate_by_envelope(raw1)  # schema/に対して妥当
                assert raw1["message_type"] == "skill_command"
                assert raw1["skill"] == "mine"
                assert raw1["args"] == {"block": "log", "count": 4}

                # 2. 成功result + skill_finished observation → 次のskill_command
                await fake_mod.send(json.dumps(_skill_result(seq=1, command_id=raw1["command_id"])))
                await fake_mod.send(json.dumps(_observation(seq=2, reason="skill_finished")))
                raw2 = json.loads(await asyncio.wait_for(fake_mod.recv(), RECV_TIMEOUT_S))

                validators.validate_by_envelope(raw2)
                assert raw2["skill"] == "craft"
                assert raw2["command_id"] != raw1["command_id"]

            # 3. 状態が永続化され、初回観測位置がbaseとして登録されている
            state_json = json.loads(config.state_file.read_text(encoding="utf-8"))
            assert state_json["memory_coords"]["base"] == {"x": 0, "y": 64, "z": 0}
            # 4. 行動履歴に成功したmineが残っている
            skills_in_history = [a["skill"] for a in state_json["action_history"]]
            assert "mine" in skills_in_history
        finally:
            await loop.stop()


@pytest.mark.asyncio
async def test_reconnect_resends_pending_command(tmp_path: Path):
    """切断中に発行されたコマンドが、Mod再接続時に再送される(仕様書§7.3)。"""

    config = BridgeConfig(
        ws_port=0,
        state_dir=tmp_path / "state",
        logs_dir=tmp_path / "logs",
        periodic_review_interval_s=3600.0,
    )
    config.state_dir.mkdir(parents=True)
    config.logs_dir.mkdir(parents=True)

    transport = _ollama_transport([{"name": "eat", "arguments": {}}])

    async with httpx.AsyncClient(transport=transport) as client:
        loop = AgentLoop(config, BridgeState(), client, get_session_logger(config.logs_dir))
        await loop.start()
        try:
            port = loop.server._server.sockets[0].getsockname()[1]
            uri = f"ws://127.0.0.1:{port}"

            # 接続してobservationだけ送り、コマンドを受け取らずに即切断する
            async with websockets.connect(uri) as fake_mod:
                await fake_mod.send(json.dumps(_observation(seq=0, reason="reconnected")))
                # 意思決定タスクの完了(=コマンド発行)を待ってから切る
                for _ in range(100):
                    await asyncio.sleep(0.05)
                    if loop._decision_task is not None and loop._decision_task.done():
                        break

            # 再接続すると保留コマンドがフラッシュされる
            async with websockets.connect(uri) as fake_mod:
                raw = json.loads(await asyncio.wait_for(fake_mod.recv(), RECV_TIMEOUT_S))
                assert raw["message_type"] == "skill_command"
                assert raw["skill"] == "eat"
        finally:
            await loop.stop()
