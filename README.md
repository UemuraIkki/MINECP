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
ollama pull qwen3:4b
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

- スキーマ・ブリッジ・Modを実装済み(ブリッジはpytest 83件パス、Modはビルド成功)。全12スキル(goto/mine/craft/smelt/place/attack/eat/equip/use_portal/build_portal/throw_ender_eye/fight_dragon)に決定論的executorが揃っている
- 実機疎通を確認済み: 実際のMinecraft開発サーバー(Fake Playerスポーン)⇄実WebSocket⇄ブリッジ⇄実Ollama(tool calling)の一周を確認。未知の名前付き地点への複数回のgoto失敗→goto baseへのフォールバック成功という決定論的な失敗復帰の流れも実機で確認済み
- **重大バグを検出・修正済み**: 直接構築したFake Player(`ServerPlayerEntity`)は`setVelocity`だけでは一切移動しない(実クライアントからの移動パケットが無いため、通常のプレイヤー物理は自力で位置を更新しない)ことが実機診断で判明。`StraightLinePathfinder`を`Entity#move`を毎tick明示的に呼ぶ実装に変更し、実機で実際に移動することを確認済み。直線+ジャンプのフォールバックである以上、壁や崖を迂回できない制約自体は残る
- **もう1つの重大な問題を検出・修正・実機確認済み**: 移動修正後の実機ランで、ワールドスポーン地点で敵Mobに繰り返し殺されるデスループを2回独立して確認。原因はLLMの判断速度(`qwen3:4b`でも1回20〜45秒)がMobの攻撃速度に対して構造的に遅すぎ、`attack`/`fight_dragon`以外のスキルには低HP時の即時反応が無かったこと。`SkillManager.tick()`に、実行中スキルの種類を問わずHP危険域(6以下)で決定論的パニック離脱に切り替える安全機構(`PanicFleeTask`)を追加し、実機で一時的なテストフック(HPを強制的に5.0まで下げる)により、実行中の`mine`が即座にキャンセルされ`FLED_FROM_COMBAT`で正常終了することを確認済み(テストフックは確認後に削除)
- **3つ目の重大バグを検出・修正・実機確認済み**: `mine`は最大5ブロック先から破壊できるが、その距離はバニラのアイテム自動回収(拾得)範囲より遠く、Fake Playerは破壊後に次のターゲット探索へ即座に移り、ドロップの元へ歩み寄ることもない。結果として`mine`は`success`かつ`mined_count`が正しく増えるのに、インベントリには何も入らないまま`wood`マイルストーンにすら到達できていなかった(実機ログで`success`3回・インベントリ空を確認)。`MineTask`に破壊直後の周辺ドロップを直接インベントリへ回収する処理を追加し、モックブリッジでの直接`mine(log,1)`送信テストでインベントリに反映されることを実機確認済み
- 次ステップ: `with_automatone=true`を既定にする(現状は直線+ジャンプのフォールバックのみで経路が詰まりやすい)、要塞三角測量の精度向上、`gear_final_check`のMod側明示シグナル化(仕様書・スキーマ更新が前提)、長時間の自律ランでcraft以降のマイルストーンに実際に到達できるかの確認
