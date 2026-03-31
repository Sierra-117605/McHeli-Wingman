# McHeliWingman — Claude Code 引継ぎプロンプト

## あなたの役割
あなたはこのプロジェクトの**プログラマー**です。
ユーザーは要件とビルド・動作確認を担当します。
コードの設計・実装・デバッグ指示はすべてあなたが行います。

---

## プロジェクト概要

**MOD名**: McHeliWingman  
**対象**: McHeli CE (Warfactory版) for Minecraft 1.12.2  
**リポジトリ**: GitHub / McHeliWingman（新規作成予定）  
**目的**: McHeli CEに対して機能を追加するサブMOD

McHeli CEはオープンソース（GPL-3）です。  
ソース: https://github.com/Warfactory-Official/McHeliCE

---

## 実装する機能（優先順）

### P0: UAV飛行距離無制限（最優先）
- デフォルト120ブロック制限を撤廃
- `config/mcheli_wingman.cfg` の `uav.controllerRange` / `uav.maxDistance` で任意値に設定可能
- MOD起動時にMcHeliCEの内部フィールドをリフレクションで上書き
- McHeliCEがオープンソースなのでクラス名・フィールド名はソースから直接確認する

### P1: 編隊飛行（フォーメーション追従）
- `/wingman follow` でウイングマンUAVがリーダー機（プレイヤー搭乗機）の後方を追従
- フォーメーションオフセットはリーダーのYawに合わせて回転
- 毎tick `LivingUpdateEvent` で目標座標を更新

### P2: ターゲット共有
- リーダー機のロックオンターゲットをフォローモード中の僚機全機に伝達
- `/wingman target share` / `clear`

### P3: AI自律攻撃
- ENGAGEモード時、射程・LOS確認後に自動発射
- `/wingman engage` / `/wingman hold`

### P4: コマンドシステム（P1と並行）
```
/wingman follow [UUID]
/wingman stop
/wingman engage
/wingman hold
/wingman target share|clear
/wingman base set|show
/wingman rtb [UUID]
/wingman status
```

### P5: 自動帰還（RTB）
- `/wingman base set` で登録座標へ自律飛行
- 帰還完了後ホバリング停止

---

## 技術スタック

| 項目 | 内容 |
|---|---|
| 言語 | Java 8 |
| ビルド | Gradle + ForgeGradle 2.3 |
| MCバージョン | 1.12.2 |
| Forgeバージョン | 14.23.5.2860 |
| 依存MOD | McHeli CE（libs/に配置） |
| McHeliCEアクセス | ソース参照 + 必要に応じてリフレクション |

---

## プロジェクト構造（目標）

```
McHeliWingman/
├── build.gradle
├── gradle.properties
├── settings.gradle
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── libs/
│   └── mcheli-ce-1.12.2.jar        ← ユーザーが配置
├── src/main/java/com/mcheliwingman/
│   ├── McHeliWingman.java           ← @Mod エントリポイント
│   ├── config/
│   │   └── WingmanConfig.java       ← Forge設定ファイル管理
│   ├── command/
│   │   └── WingmanCommand.java      ← /wingman コマンド
│   ├── handler/
│   │   ├── TickHandler.java         ← LivingUpdateEvent
│   │   └── RangeOverrideHandler.java← P0: 飛行距離上書き
│   ├── wingman/
│   │   └── WingmanController.java   ← FSM本体（IDLE/FOLLOWING/ENGAGING/RETURNING）
│   └── util/
│       ├── FormationCalc.java       ← フォーメーション座標計算
│       └── McheliReflect.java       ← McHeliCE内部へのリフレクションユーティリティ
└── src/main/resources/
    ├── mcmod.info
    └── pack.mcmeta
```

---

## ウイングマンFSM状態遷移

```
IDLE
 └─ /wingman follow ──→ FOLLOWING
                            └─ /wingman engage ──→ ENGAGING
                            └─ /wingman rtb    ──→ RETURNING
                                                      └─ 帰還完了 ──→ IDLE
 ←─ /wingman stop ─────────────────────────────────────────────────────┘
```

---

## 作業の進め方

1. **まずMcHeliCEのソースを読む**  
   `https://github.com/Warfactory-Official/McHeliCE` のsrc以下を確認し、  
   以下のクラス・フィールドを特定してから実装に入る：
   - UAVエンティティのクラス名
   - 飛行距離制限フィールド（`controllerRange` / `maxDistance` 相当）
   - ロックオンターゲットのフィールド
   - 武装発射メソッドのシグネチャ
   - 機体速度・Yaw角の取得方法

2. **M1: 空MODをビルドして動作確認**  
   `@Mod` エントリポイントだけ持つ最小構成でForgeに読み込まれることを確認

3. **M1.5: P0（飛行距離無制限）を実装**  
   最も単独で価値があり、かつ実装が比較的シンプルなため最初に完成させる

4. **M2以降: コマンド → 追従 → ターゲット共有 → 攻撃 → RTBの順で実装**

---

## 制約・注意事項

- ビルド・環境構築・jarの配置・動作確認はユーザー側が行う
- Claude Codeはコード生成・設計・デバッグ指示を担当
- McHeliCEのライセンスはGPL-3なので本MODも同ライセンスで公開する想定
- リフレクションを使う箇所はMcHeliCEのバージョンアップで壊れる可能性があることをコメントに明記
- null安全を徹底する（McHeliCE側エンティティがnullの場合は必ずスキップ）
- サーバーサイドで完結させる（クライアント専用処理は原則不要）

---

## 参考資料

- McHeliCE ソース: https://github.com/Warfactory-Official/McHeliCE
- McHeliCE Wiki: https://github.com/Warfactory-Offical/McHeliCE/wiki
- Forge 1.12.2 MDK: https://files.minecraftforge.net/
- 要件定義書: REQUIREMENTS.md（同リポジトリに同梱予定）
