# schema/ — 通信スキーマ(単一の真実)

Mod⇄ブリッジ間の全WebSocketメッセージのJSON Schema定義。JSON Schema draft 2020-12。

| ファイル | 内容 |
|---|---|
| `common.schema.json` | 共通$defs: MessageEnvelope(message_type / timestamp_ms / seq)、Vec3、BlockPos、Dimension、ItemStack、NamedLocation、Milestone |
| `observation.schema.json` | Mod → ブリッジ。要約済み観測 |
| `skill_command.schema.json` | ブリッジ → Mod。スキル命令(スキル名・引数の定義もここ) |
| `skill_result.schema.json` | Mod → ブリッジ。スキル結果 |
| `event.schema.json` | Mod → ブリッジ。割り込みイベント |
| `failure_codes.schema.json` | 失敗理由コード列挙 |

## ルール(仕様書§7.2、§12)

1. **スキーマ変更は必ずこのディレクトリを先に更新**してから、Mod・ブリッジ両実装に反映する。
2. スキーマに現れないアドホックなメッセージフィールドの追加は**禁止**(例外: `skill_result.data` はスキル固有返却用に開放されているが、内容は各スキーマの`description`に記載すること)。
3. 全メッセージはUTF-8のJSONテキストフレーム1件 = 1メッセージ。
4. `$ref`は同一ディレクトリ内の相対参照。バリデータは本ディレクトリをベースURIとして解決すること。

## ワイヤ形式

- ブリッジがWebSocketサーバー(既定: `ws://127.0.0.1:8765`)、Modがクライアント。
- 各メッセージはエンベロープ(`message_type`, `timestamp_ms`, `seq`)+ 種別ごとのフィールドをトップレベルにフラット展開した1つのJSONオブジェクト。
- `seq`は送信側プロセスごとに0から単調増加。再接続時もプロセスが生きていれば継続する。
