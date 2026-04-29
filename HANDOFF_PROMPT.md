# McHeli Wingman — Claude Code 引継ぎプロンプト
> 更新: 2026-04-30

## あなたの役割

プログラマー。コードの設計・実装・デバッグ指示を担当する。  
ユーザーはビルド・動作確認・要件判断を担当する。

---

## プロジェクト概要

**MOD名**: McHeli Wingman  
**対象**: McHeli 1.1.4 for Minecraft Forge 1.12.2  
**リポジトリ**: https://github.com/Sierra-117605/McHeli-Wingman  
**CurseForge**: https://www.curseforge.com/minecraft/mc-mods/mcheli-wingman  
**状態**: v1.1.0 リリース済み・機能実装ほぼ完了

McHeli 本体は非オープンソース（Murachiki 氏作）。内部アクセスはすべてリフレクション経由。

---

## ビルド手順

```
./gradlew.bat reobfJar
```

**`jar` タスクは絶対に使わない**。MCP フィールド名のまま出力され `NoSuchFieldError: IRON` でクラッシュする。  
成果物: `build/libs/McHeliWingman-<version>.jar`

---

## ソース構造

```
src/main/java/com/mcheliwingman/
├── McHeliWingman.java              @Mod エントリポイント
├── asm/
│   ├── WingmanPlugin.java          FMLLoadingPlugin（ASMバイトコード変換）
│   └── WingmanTransformer.java
├── block/
│   ├── MarkerType.java             enum (IStringSerializable実装)
│   ├── WingmanMarkerBlock.java     PropertyEnum<MarkerType> + getActualState()
│   └── WingmanMarkerTileEntity.java
├── client/
│   ├── GuiBaseConfig.java          BASEマーカーGUI（タキシールート・子マーカー）
│   ├── GuiMarkerConfig.java        マーカー設定GUI
│   ├── GuiNewRoute.java            タキシールート編集GUI
│   ├── GuiWingmanPanel.java        僚機パネル（Numpad 0）
│   ├── GuiWingmanPlanner.java      ミッションプランナーGUI（/wingman gui）
│   ├── WingmanGuiHandler.java      NetworkRegistry.registerGuiHandler
│   └── WingmanKeyHandler.java      キーバインド登録
├── command/
│   └── WingmanCommand.java         /wingman コマンド全種
├── config/
│   └── WingmanConfig.java          設定ファイル管理
├── handler/
│   ├── AutonomousFlightHandler.java 自律飛行ステート機械（毎tick）
│   ├── ChunkLoadHandler.java        チャンク強制ロード管理
│   ├── ClientAutopilotHandler.java  クライアント側ヨー・スロットル補正（Phase.END）
│   ├── RangeOverrideHandler.java    UAV距離制限リフレクション上書き
│   ├── UavChunkStreamer.java         UAV追尾チャンクロード
│   └── WingmanTickHandler.java      僚機毎tick処理（フォーメーション・攻撃）
├── mission/
│   ├── AutonomousState.java         22ステートenum
│   ├── MissionNode.java             FLY_TO / ATTACK / LOITER ノード
│   ├── MissionOrder.java            発令情報（baseId・parkingId・タスクリスト等）
│   ├── MissionPlan.java             JSON保存・読込
│   ├── MissionType.java             CAP / CAS / STRIKE / ESCORT / RECON / FERRY
│   └── TaxiRoute.java               タキシールートデータクラス
├── network/
│   ├── PacketAutopilotVisual.java   S→C ヨー・スロットル補正パケット
│   ├── PacketBaseAction.java        BASE GUI → サーバー操作
│   ├── PacketMarkerUpdate.java      マーカー設定保存
│   ├── PacketMissionAction.java     ミッション操作
│   ├── PacketOpenBaseGui.java       S→C BASEマーカーGUI開封データ
│   ├── PacketOpenMarkerGui.java     S→C マーカーGUI開封データ
│   ├── PacketPlannerData.java       ミッションプランナーデータ
│   ├── PacketRouteAction.java       タキシールートCRUD
│   ├── PacketWingmanPanelAction.java 僚機パネル操作
│   ├── PacketWingmanPanelData.java  僚機パネルデータ
│   ├── PacketWingmanPanelOpen.java  僚機パネル開封
│   └── WingmanNetwork.java          SimpleNetworkWrapper 登録
├── registry/
│   ├── MarkerRegistry.java          world別マーカー管理
│   └── TaxiRouteRegistry.java       world別タキシールート管理
├── util/
│   ├── McheliReflect.java           McHeli内部アクセス（メソッド/フィールドキャッシュ）
│   └── WingmanUavRegistry.java      UAVエンティティ追跡
└── wingman/
    ├── WingmanEntry.java            僚機1機分の状態（autoState含む）
    ├── WingmanRegistry.java         UUID → WingmanEntry マップ
    └── WingmanState.java            IDLE / FOLLOWING
```

---

## 重要な実装パターン

### ヨー・スロットル補正（搭乗中の自律飛行）
McHeli の `onUpdateAircraft()` がクライアント側キー入力でヨーとスロットルを毎tick上書きする。  
対策: `WingmanTickHandler`（サーバー）が `PacketAutopilotVisual` を S→C 送信。`ClientAutopilotHandler` が `TickEvent.ClientTickEvent` の **Phase.END** で再上書き。

### マーカーブロックのタイプ別テクスチャ
`PropertyEnum<MarkerType> TYPE` をブロックステートに持ち、`getActualState()` で TileEntity からタイプを読んで返す。メタデータは使わない（常に0）。

### McHeli 内部アクセス
`McheliReflect` でメソッド・フィールドを初回キャッシュし、以降は `invoke()` / `get()` で呼ぶ。  
`setRotYaw()` `getCurrentThrottle()` `setCurrentThrottle()` `isHelicopter()` `isVtol()` など。

---

## 既知の未解決バグ（KNOWLEDGE.md・TODO.md 参照）

- **TAKEOFF_ROLL yaw drift**: 離陸時に斜めに滑走。`setRotYaw()` 直接呼び出しで解決可能
- **LANDING nose-up / 後輪めり込み**: 近距離（30〜50ブロック先）+ 大きな下方オフセットターゲットで改善可能
- **着陸位置精度**: CIRCUIT サイズが広すぎるとチャンク外に逸脱
- **VTOL_TAKEOFF / VTOL_LAND**: 修正済みだが動作確認未実施

---

## 参考

- 詳細仕様: `REQUIREMENTS.md`
- ハマりポイント集: `KNOWLEDGE.md`
- 未解決タスク: `TODO.md`
- README（ユーザー向け）: `README.md`
