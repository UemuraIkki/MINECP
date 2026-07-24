# minecp_bridge

Minecraft自律プレイLLMエージェントの「ブリッジ」プロセス(Python)。
仕様は `../docs/minecraft-llm-agent-仕様書.md`、通信メッセージは `../schema/` が単一の真実。
記憶座標の扱いは `../docs/adr/ADR-0001-observation-memory-coords.md` を参照。

```
[Fabric Mod] <-- WebSocket --> [minecp_bridge (このパッケージ)] <-- HTTP --> [Ollama]
```

## セットアップ

Python 3.11 以上が必要です。

```powershell
cd bridge
python -m venv .venv
.venv\Scripts\activate
pip install -e .[dev]
```

(uv を使う場合: `uv venv && uv pip install -e .[dev]`)

## テスト

```powershell
pytest
```

Ollama・Minecraft(Mod)は不要。LLM呼び出しは `httpx.MockTransport` でモックしている。

## 起動

Ollamaが起動していること(既定 `http://127.0.0.1:11434`、モデル `qwen2.5:14b` を pull 済みであること)。

```powershell
python -m minecp_bridge
```

既定で `ws://127.0.0.1:8765` でWebSocketサーバーを起動し、Fabric Modからの接続を待ち受ける。

## 設定

設定は「既定値 < `bridge/config.toml` < 環境変数(`MINECP_BRIDGE_*`)」の優先順位でマージされる。
`bridge/config.toml` の場所は環境変数 `MINECP_BRIDGE_CONFIG` で変更可能。

主な項目(`src/minecp_bridge/config.py` の `BridgeConfig` 参照):

| 項目 | 環境変数 | 既定値 |
|---|---|---|
| WebSocketホスト | `MINECP_BRIDGE_WS_HOST` | `127.0.0.1` |
| WebSocketポート | `MINECP_BRIDGE_WS_PORT` | `8765` |
| Ollama URL | `MINECP_BRIDGE_OLLAMA_URL` | `http://127.0.0.1:11434` |
| Ollamaモデル | `MINECP_BRIDGE_OLLAMA_MODEL` | `qwen2.5:14b` |
| 定期見直し間隔(秒) | `MINECP_BRIDGE_PERIODIC_REVIEW_INTERVAL_S` | `120.0` |
| LLM出力再生成の最大回数 | `MINECP_BRIDGE_MAX_LLM_RETRIES` | `3` |
| 反省ループ発動閾値(同一スキル連続失敗回数) | `MINECP_BRIDGE_REFLECTION_FAILURE_THRESHOLD` | `3` |
| アイテム消滅タイマー(秒) | `MINECP_BRIDGE_ITEM_DESPAWN_S` | `300.0` |

例(`bridge/config.toml`):

```toml
ollama_model = "qwen2.5:32b"
ws_port = 8765
```

## 構成

| ファイル | 責務 |
|---|---|
| `messages.py` | `schema/` と1:1のPydanticモデル(4メッセージ種別・FailureCode・共通型) |
| `schema_validation.py` | `schema/*.schema.json` を使ったJSON Schema検証(受信メッセージを最初に通す関門) |
| `ws_server.py` | WebSocketサーバー。スキーマ検証 → Pydanticモデル化 → ディスパッチ。切断・再接続に耐える |
| `state.py` | 状態管理 + `state/state.json` へのアトミックな永続化 |
| `milestones.py` | マイルストーンDAG(14ノード)と、観測・記憶からの達成判定ヒューリスティック |
| `prompts.py` | システムプロンプト・状況プロンプト・反省プロンプト・死亡リカバリプロンプトの構築(英語) |
| `llm.py` | Ollama `/api/chat` クライアント。tool calling→スキーマ検証→最大3回再生成→フォールバック(goto base) |
| `agent_loop.py` | 意思決定ループ本体(即時判断・定期見直し・反省ループ・死亡リカバリ) |
| `logging_setup.py` | 1セッション1JSONLファイルへのログ出力 |
| `config.py` | 設定(既定値 / TOML / 環境変数) |
| `__main__.py` | `python -m minecp_bridge` エントリポイント |

## 設計上の判断・前提(未解決事項含む)

- **マイルストーン達成判定はヒューリスティックである**: `milestones.py` はインベントリ内の代表的なアイテムid・`progress`カウンタ・一部のvanilla実績idから判定する。特に以下は暫定的な近似:
  - `iron_gear`: 鉄ピッケル+鉄剣所持のみで判定(防具は問わない)。
  - `gear_final_check`: ダイヤの剣+防具4部位所持、という簡易ヒューリスティック。実際にはMod側からより明示的な「装備最終確認完了」フラグ(例: `progress.advancements` への専用エントリ追加)を仕様書・スキーマに追加する方が堅牢。**要検討・要スキーマ拡張(schema/を先に更新の上)**。
  - `nether_portal` / `stronghold_found`: ブリッジの記憶座標が登録されているか(`MilestoneContext.has_nether_portal` / `has_stronghold_location`)で判定する。`agent_loop.py`の`_register_discovered_locations`が、`nearby.points_of_interest`に`nether_portal`/`stronghold_block`種別のPOIが現れた時点(=Fake Playerがその構造物の16ブロック以内に実際に立った時点)で記憶座標とコンテキストを配線する。build_portal/throw_ender_eyeの`skill_result`自体は座標を含まない(位置オラクル禁止)ため、これは`base`と同じく「実観測に基づく発見」であり、事前に座標を知っているわけではない。**未対応**: throw_ender_eyeが返す方向ベクトル(`data.direction`)を使った複数地点からの三角測量は未実装で、要塞の座標は現状、実際に近づいて`stronghold_block`が観測範囲に入るまで登録されない。
- **反省ループの「3回連続失敗」判定**: スキル名ごとの連続失敗カウンタ(`state.consecutive_failures`)で判定する。引数が異なっていても同一スキル名なら連続とみなす(仕様書の記述上、引数一致までは要求していないため)。
- **死亡リカバリのアイテム消滅タイマー**: バニラ既定の5分(`item_despawn_s=300`)を既定値としている。ゲームルール変更時は設定で上書き可能。
- **`skill_command.args`のバリデーション**: LLMのtool呼び出し引数は各スキルのPydantic Argsモデル(`SKILL_ARGS_CLASSES`)で検証する。これは`schema/skill_command.schema.json`の`oneOf`条件と同じ制約を表現している。
- **WebSocket再接続時の再送**: 直近1件の`skill_command`のみを保持して再送する(複数キューはしない)。仕様書§4.1.3「同時実行は1スキルのみ・新命令優先」と整合させるため。
- **base座標の初期登録**: ADR-0001の帰結どおり、初回observationの座標を`base`として登録する。

## 未解決事項

- `mod/`側は全12スキル実装済み・ビルド成功済みだが、実際のMinecraftサーバー・実Ollamaを繋いだ結合テストは未実施(本パッケージのテスト・`test_e2e_loop.py`はいずれもモックWebSocketクライアント/モックOllamaのみで、実機は使わない)。
- `gear_final_check`の判定ロジックをMod側の明示的なシグナルに置き換えるかどうかは、仕様書・スキーマの更新を伴うため別途意思決定が必要。
- throw_ender_eyeの方向ベクトルを使った要塞座標の三角測量は未実装(上記`stronghold_found`の項を参照)。
- 実際のOllama tool calling応答フォーマットは環境(Ollamaバージョン・モデル)によって`arguments`が文字列/オブジェクトいずれで返るか揺れることがある。`llm.py`は両方を許容しているが、実機での検証は未実施。
