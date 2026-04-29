# McHeli Wingman — 機能仕様書
> Version 1.1  
> 更新: 2026-04-30（実装済みコードと照合）

---

## 1. プロジェクト概要

**MOD名**: McHeli Wingman  
**対象**: McHeli 1.1.4 for Minecraft Forge 1.12.2  
**リポジトリ**: https://github.com/Sierra-117605/McHeli-Wingman  
**目的**: McHeli にAI僚機・自律飛行ミッション・マーカーブロック基地設定を追加するアドオン

McHeli 本体の武器性能（射程・ダメージ等）は一切変更しない。

---

## 2. 動作環境

| 項目 | バージョン |
|---|---|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2847 以上 |
| McHeli | 1.1.4 |

---

## 3. 実装済み機能

### F1: UAV 通信圏外制限の撤廃 ✅

McHeli 標準の UAV 通信距離制限をリフレクションで上書きし、UAV ステーションから任意の距離でも UAV を操縦できるようにする。

- `RangeOverrideHandler`: McHeli 内部フィールドをリフレクションで上書き
- `UavChunkStreamer`: UAV 追尾チャンクロード（`ForgeChunkManager`）
- `ChunkLoadHandler`: チャンク強制ロードチケット管理
- `WingmanUavRegistry`: UAV エンティティ追跡レジストリ

---

### F2: AI 僚機（CCA）✅

McHeli 機体をプレイヤー機に随伴させる編隊飛行システム。

**状態機械**: `WingmanState` — `IDLE` / `FOLLOWING`  
**データ**: `WingmanEntry`（状態・設定・自律ターゲット等を保持）  
**レジストリ**: `WingmanRegistry`（UUID → WingmanEntry マップ）  
**tick処理**: `WingmanTickHandler`（毎tick フォーメーション座標更新・ヨー制御・スロットル制御・ギア制御）

**編隊**
- 側方・高度・後退距離をリアルタイムで変更可能（`/wingman dist`）
- 最大僚機数 1〜64（`/wingman maxwings`）

**攻撃**
- `FOLLOWING` → `ENGAGING`（指定ターゲット攻撃）
- 自動攻撃モード（近くの敵 Mob を自律検知・攻撃）
- 武器種指定・高度制限（min/max Y）

**クライアント補正**: `ClientAutopilotHandler` + `PacketAutopilotVisual`  
プレイヤーが搭乗中の僚機に対し、S→C パケットでヨーとスロットルを毎tick補正（McHeli クライアント側入力より Phase.END で後から上書き）

---

### F3: マーカーブロック ✅

自律飛行に必要な地理情報をワールドに登録するブロック。

**ブロック**: `WingmanMarkerBlock` + `WingmanMarkerTileEntity`  
**タイプ**: `MarkerType` enum（`IStringSerializable` 実装）

| タイプ | 用途 |
|---|---|
| `BASE` | 基地アンカー。子マーカーをまとめる親マーカー |
| `PARKING` | 駐機場 |
| `RUNWAY_A` | 滑走路端A（離陸起点・着陸終点） |
| `RUNWAY_B` | 滑走路端B（タッチダウンゾーン） |
| `WAYPOINT` | 空中巡航経由点 |
| `HELIPAD` | ヘリ・VTOL機専用垂直離着陸スポット |
| `HELIPAD_B` | ヘリパッド方向指示マーカー（機首向き基準） |

**テクスチャ**: タイプ別 16×16 PNG（`blockstates` の `getActualState()` パターン）  
**レジストリ**: `MarkerRegistry`（world → pos → MarkerInfo マップ）  
**GUI**:
- BASE マーカー右クリック → `PacketOpenBaseGui` → `GuiBaseConfig`（タキシールート・子マーカー・機体一覧）
- その他マーカー右クリック → `PacketOpenMarkerGui` → `GuiMarkerConfig`（ID・タイプ・baseId・parkingHeading）
- タイプ変更: `/wingman marker type <種別>`

---

### F4: タキシールート ✅

駐機場 → 滑走路（またはヘリパッド）間の地上移動ルートを定義。

**データ**: `TaxiRoute`（routeId・parkingId・runwayId・runwayBId・waypointIds・arrivalWaypointIds・arrivalRunwayId・parkingHeading）  
**レジストリ**: `TaxiRouteRegistry`（world・baseId 別管理）  
**GUI**: `GuiBaseConfig` の「Taxi Routes」タブ / `GuiNewRoute`（ルート編集）  
**パケット**: `PacketRouteAction`（追加・削除・保存）

---

### F5: 自律飛行ミッションシステム ✅

機体に対してミッションを発令すると、一連の行動を自動実行する。

**状態機械**: `AutonomousState` enum（22ステート）

```
NONE → TAXI_OUT → ALIGN → TAKEOFF_ROLL → CLIMB
     → ENROUTE (FLY_TO ノード巡回)
     → TRANSIT_TO → ON_STATION → STRIKE_PASS
     → DESCEND → CIRCUIT_DOWNWIND → CIRCUIT_BASE → CIRCUIT_FINAL → LANDING
     → TAXI_IN → PARKED

VTOL系: VTOL_TAKEOFF / VTOL_LAND（HELIPAD マーカー使用時）
```

**ハンドラー**: `AutonomousFlightHandler`（毎tick 各ステートを `tickOrder*()` メソッドで処理）  
**ミッションプラン**: `MissionPlan`（JSON保存・読込）+ `MissionNode`（FLY_TO / ATTACK / LOITER）  
**ミッション種別**: `MissionType` — CAP / CAS / STRIKE / ESCORT / RECON / FERRY  
**発令**: `MissionOrder`（baseId・parkingId・タスクリスト等を保持）  
**GUI**: `GuiWingmanPlanner`（`/wingman gui`）+ `GuiNewRoute`

**機体判定**: `McheliReflect.isHelicopter()` / `isVtol()` / `canUseHelipad()`  
- VTOL 機が HELIPAD マーカー駐機時 → `VTOL_TAKEOFF` / `VTOL_LAND`  
- VTOL 機が滑走路駐機時 → 通常 `TAKEOFF_ROLL` / `LANDING`

**コマンド**:
```
/wingman order dispatch <uuid>   ミッション発令
/wingman order abort [uuid]      中断（省略で全機）
/wingman order status            実行状態表示
/wingman order park <uuid>       駐機場へ帰還
/wingman gui                     ミッションプランナーを開く
```

---

### F6: 僚機パネル GUI ✅

Numpad 0 でゲーム内から開くリアルタイム制御パネル。

**クラス**: `GuiWingmanPanel`  
**キーバインド**: `WingmanKeyHandler`（`/wingman gui` 相当をキーで呼び出し）  
**機能**: Auto / Hold / Stop ボタン、武器種選択（< >）、編隊パラメータ スピナー

---

## 4. コマンド一覧

```
/wingman follow [uuid]          最寄りの機体（または指定UUID）を僚機に設定
/wingman stop                   自機に随伴中の僚機を全解除
/wingman status                 登録済み僚機の一覧と状態を表示
/wingman dist <横> <高度> <後退> 編隊距離をリアルタイム変更
/wingman maxwings <数>           最大僚機数 (1〜64)
/wingman engage [uuid]          僚機に攻撃を命令
/wingman auto                   自動攻撃モード
/wingman hold                   攻撃中止・編隊復帰
/wingman weapon [種別|clear]    武器種指定
/wingman minalt [Y]             攻撃最低高度 (0=制限なし)
/wingman maxalt [Y]             攻撃最高高度 (0=制限なし)
/wingman alt clear              高度制限リセット
/wingman spawnuav [種別]        UAVをプレイヤー正面にスポーン
/wingman marker list            マーカー一覧表示
/wingman marker type <種別>     見ているマーカーのタイプを設定
/wingman marker id <ID>         見ているマーカーのIDを設定
/wingman order dispatch <uuid>  ミッション発令
/wingman order abort [uuid]     ミッション中断
/wingman order status           ミッション状態表示
/wingman order park <uuid>      駐機場へ帰還
/wingman gui                    ミッションプランナーGUIを開く
```

---

## 5. ASM

`WingmanPlugin` / `WingmanTransformer`: McHeli クラスの動的バイトコード変換（`FMLLoadingPlugin`）。リフレクションで対応できない箇所への介入に使用。

---

## 6. 未解決・改善余地

- TAKEOFF_ROLL yaw drift（斜め滑走）→ `setRotYaw()` 直接呼び出しで解決可能
- LANDING 後 nose-up・後輪めり込み → 近距離 + 大きな下方オフセットターゲットで改善可能
- 着陸位置精度（チャンク外逸脱時の CIRCUIT サイズ）
- VTOL_TAKEOFF / VTOL_LAND の動作確認（修正後未テスト）
