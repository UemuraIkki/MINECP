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

## セットアップ

各サブディレクトリのREADMEを参照(実装の進行に合わせて追記)。
