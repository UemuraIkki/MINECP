"""Timestamped JSONL session logging (仕様書§4.2.6, §10).

Every prompt, every LLM response, and every skill command/result is written
as one JSON object per line to a single per-session file under ``logs/``,
so a full clear attempt can be reconstructed after the fact.
"""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any


def _session_filename() -> str:
    return time.strftime("session-%Y%m%dT%H%M%S.jsonl", time.gmtime())


class SessionLogger:
    def __init__(self, logs_dir: Path, filename: str | None = None):
        self.logs_dir = logs_dir
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.path = self.logs_dir / (filename or _session_filename())
        self._lock = threading.Lock()

    def _write(self, record: dict[str, Any]) -> None:
        record.setdefault("logged_at_ms", int(time.time() * 1000))
        line = json.dumps(record, ensure_ascii=False, default=str)
        with self._lock:
            with self.path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")

    def log(self, kind: str, **fields: Any) -> None:
        self._write({"kind": kind, **fields})

    def log_prompt(self, prompt_kind: str, system_prompt: str, user_prompt: str) -> None:
        self.log("prompt", prompt_kind=prompt_kind, system_prompt=system_prompt, user_prompt=user_prompt)

    def log_llm_exchange(self, messages: list[dict[str, Any]], response: dict[str, Any] | None, error: str | None) -> None:
        self.log("llm_exchange", messages=messages, response=response, error=error)

    def log_skill_command(self, command: dict[str, Any]) -> None:
        self.log("skill_command", command=command)

    def log_skill_result(self, result: dict[str, Any]) -> None:
        self.log("skill_result", result=result)

    def log_observation(self, observation: dict[str, Any]) -> None:
        self.log("observation", observation=observation)

    def log_event(self, event: dict[str, Any]) -> None:
        self.log("event", event=event)

    def log_reflection(self, skill: str, threshold: int) -> None:
        self.log("reflection_triggered", skill=skill, threshold=threshold)

    def log_death_recovery(self, **fields: Any) -> None:
        self.log("death_recovery", **fields)


def get_session_logger(logs_dir: Path) -> SessionLogger:
    return SessionLogger(logs_dir)
