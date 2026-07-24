# MINECP — Minecraft自律プレイLLMエージェント

Ollama上のローカルLLM(Qwen系想定)に人間の介入なしでMinecraftをプレイさせ、エンダードラゴン討伐(ゲームクリア)を達成させるプロジェクト。

仕様の詳細は [docs/minecraft-llm-agent-仕様書.md](docs/minecraft-llm-agent-仕様書.md) を参照。

## アーキテクチャ

```
[Minecraftサーバー + Fabric Mod] ⇄ WebSocket ⇄ [ブリッジ(Python)] ⇄ HTTP ⇄ [Ollama]
```

- **mod/** — Fabric Mod(Java, MC 1.20.1)。Fake Player管理・観測生成・スキルの決定論的実行のみ。判断ロジックは持たない。
- **bridge/** — Pythonブリッジ。計画状態・マイルストーン管理・永続メモリ・反省ループ・プロンプト構築・LLM呼び出し。
- **schema/** — Mod⇄ブリッジ間の通信メッセージのJSON Schema。**単一の真実**。変更は必ずここを先に更新する。
- **docs/** — 仕様書、ADR(決定記録)、プロンプト設計メモ。
- **logs/** — 実行ログ出力先(git管理外)。

## 開発フェーズ

| フェーズ | ゴール |
|---|---|
| P1 | Fake Player生成、WebSocket疎通、goto/mineが動作 |
| P2 | 人間介入なしで鉄ツール一式を作成 |
| P3 | ネザーポータル建設、ブレイズロッド7本回収 |
| P4 | エンダーアイ作成、要塞発見、ポータル起動 |
| P5 | ドラゴン討伐。ランダムシードでのクリア達成 |

## セットアップ・起動手順

3プロセスを次の順で起動する(詳細は各サブディレクトリのREADME参照)。

### 1. Ollama

```
ollama pull qwen2.5:14b
ollama serve   # 既定: http://127.0.0.1:11434
```

### 2. ブリッジ(先に起動 — WebSocketサーバー側)

```
cd bridge
python -m venv .venv && .venv\Scripts\activate
pip install -e .[dev]
python -m minecp_bridge   # 既定: ws://127.0.0.1:8765
```

### 3. Minecraftサーバー + Mod

```
cd mod
gradlew build                          # jar生成(build/libs/minecp-*.jar)
gradlew runServer                      # 開発サーバー起動(Modがブリッジへ接続)
gradlew -Pwith_automatone=true runServer   # Automatone経路探索を有効化する場合
```

Mod起動後、Fake Playerが自動スポーンし、観測がブリッジへ流れ、LLMの意思決定でスキルが実行される。

## 現在の状態

- スキーマ・ブリッジ・Modの実装済み(ブリッジはpytest 50件パス、Modはビルド成功)
- goto / mine / craft / place / eat / equip は完全実装。attack / use_portal / build_portal / fight_dragon は決定論スクリプトのP1版、smelt / throw_ender_eye はスケルトン
- 次ステップ: フェーズP1の実機疎通(Fake Playerスポーン→WebSocket接続→goto/mine動作確認)
