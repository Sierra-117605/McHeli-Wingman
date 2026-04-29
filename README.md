# McHeli Wingman

[日本語](#日本語) | [English](#english)

---

## 日本語

### 概要

**McHeli Wingman** は、Minecraft Forge 1.12.2 向け航空機 Mod「[McHeli](https://github.com/Murachiki/McHeli-Mod)」のアドオン Mod です。
AI 僚機・UAV 距離制限撤廃・マーカーブロックによる基地設定・自律飛行ミッションシステムを追加します。

McHeli 本体の武器性能（射程・ダメージ等）は一切変更しません。

---

### 動作環境

| 項目 | バージョン |
|------|-----------|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2847 以上 |
| McHeli | 1.1.4 |

---

### インストール

1. [Releases](../../releases) から最新の `.jar` をダウンロード
2. `.minecraft/mods/` フォルダに配置

---

### 機能一覧

#### UAV 飛行距離制限の撤廃

McHeli 標準の UAV 通信圏外制限を撤廃します。UAV ステーションから離れた場所でも UAV の操作を継続できます。

---

#### AI 僚機（CCA）

他の航空機をプレイヤー機に随伴させます。

- 編隊飛行（横・高度・後退距離を自由に設定）
- 1 機につき最大 64 機の僚機
- 指定エンティティへの協調攻撃
- 自動攻撃モード（近くの敵 Mob を自動で攻撃）
- 武器種の指定・高度制限の設定

---

#### マーカーブロック

自律飛行に必要な地理情報をワールドに登録するブロックです。
設置後にクリックすると GUI が開き、ID や基地への紐付けを設定できます。
Shift+クリックまたは `/wingman marker type` でタイプを変更できます。

| タイプ | 説明 |
|--------|------|
| `base` | 基地アンカー。GUI の起点となる親マーカー |
| `parking` | 駐機場 |
| `runway_a` | 滑走路 A 端（離陸起点・着陸終点） |
| `runway_b` | 滑走路 B 端（タッチダウンゾーン） |
| `waypoint` | 空中巡航経由点 |
| `helipad` | ヘリコプター・VTOL 機専用垂直離着陸スポット |
| `helipad_b` | ヘリパッド方向指示マーカー（機首向きの基準） |

---

#### タキシールート

BASE マーカーをクリックして開く GUI の「Taxi Routes」タブで設定します。
駐機場 → 滑走路（またはヘリパッド）間の地上移動ルートを登録します。

---

#### 自律飛行ミッション

機体に対してミッションを発令すると、一連の行動を自動で実行します。
搭乗したままミッションを発令することもできます（自律飛行中はスロットル・機首方向が自動制御されます）。

対応機種：固定翼機・ヘリコプター・VTOL 機

**ミッションフロー（例）**
1. 駐機場からタキシー
2. 滑走路 A 端から離陸（ヘリ・VTOL 機は垂直離陸）
3. 指定ウェイポイントを経由して巡航
4. 目標エリアで攻撃周回（ON_STATION → STRIKE_PASS）
5. 燃料残量・時間制限に応じて自動 RTB
6. 着陸・タッチダウン・駐機

---

### コマンド一覧

すべてのコマンドは `/wingman <サブコマンド>` の形式です。

#### 僚機（CCA）

| コマンド | 説明 |
|----------|------|
| `/wingman follow [uuid]` | 最寄りの航空機（または指定 UUID）を僚機に設定 |
| `/wingman stop` | 自機に随伴中の僚機をすべて解除 |
| `/wingman status` | 登録済み僚機の一覧と状態を表示 |

#### 編隊

| コマンド | 説明 |
|----------|------|
| `/wingman dist <横> <高度> <後退>` | 編隊の各距離をリアルタイムで変更（単位: ブロック） |
| `/wingman maxwings <数>` | 1 機が持てる最大僚機数を設定（1〜64） |

#### 攻撃

| コマンド | 説明 |
|----------|------|
| `/wingman engage [uuid]` | 僚機に指定 UUID のエンティティを攻撃させる（省略時はロックオン対象） |
| `/wingman auto` | 僚機を自動攻撃モードに切り替え |
| `/wingman hold` | 攻撃を中止し、編隊に戻る |
| `/wingman weapon [種別\|clear]` | 使用する武器種を指定（省略で現在の設定を表示） |
| `/wingman minalt [Y]` | 攻撃時の最低高度を設定（0 = 制限なし） |
| `/wingman maxalt [Y]` | 攻撃時の最高高度を設定（0 = 制限なし） |
| `/wingman alt clear` | 高度制限をリセット |

#### UAV

| コマンド | 説明 |
|----------|------|
| `/wingman spawnuav [種別]` | UAV をプレイヤーの正面にスポーン |

#### マーカー

| コマンド | 説明 |
|----------|------|
| `/wingman marker list` | ワールド内のマーカー一覧を表示 |
| `/wingman marker type <種別>` | 見ているマーカーブロックの種別を設定 |
| `/wingman marker id <ID>` | 見ているマーカーブロックに ID を設定 |

#### ミッション

| コマンド | 説明 |
|----------|------|
| `/wingman order dispatch <uuid>` | 指定機体にミッションを発令 |
| `/wingman order abort [uuid]` | ミッションを中断（省略で全機） |
| `/wingman order status` | 実行中のミッション状態を表示 |
| `/wingman order park <uuid>` | 指定機体を駐機場へ帰還させる |
| `/wingman gui` | ミッションプランナー GUI を開く |

---

### 注意事項

- 本 Mod は McHeli の非公式アドオンです。McHeli 本体の開発者様（Murachiki 氏）とは無関係です
- McHeli 本体の武器性能は変更しません
- 自律飛行は実験的な機能です。地形・機体種別によっては想定外の挙動が起こる場合があります

---

## English

### Overview

**McHeli Wingman** is an addon mod for [McHeli](https://github.com/Murachiki/McHeli-Mod), an aircraft mod for Minecraft Forge 1.12.2.
It adds AI wingmen, UAV range extension, marker-based base configuration, and an autonomous flight mission system.

This addon does **not** modify any McHeli weapon stats (range, damage, etc.).

---

### Requirements

| Item | Version |
|------|---------|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2847+ |
| McHeli | 1.1.4 |

---

### Installation

1. Download the latest `.jar` from [Releases](../../releases)
2. Place it in your `.minecraft/mods/` folder

---

### Features

#### UAV Range Extension

Removes McHeli's default UAV communication range limit. UAVs remain fully controllable even outside normal range.

---

#### AI Wingman (CCA)

Assign other aircraft as wingmen to fly in formation and fight alongside you.

- Formation flight with configurable spacing (side / altitude / rear offset)
- Up to 64 wingmen per aircraft
- Coordinated attacks on designated targets
- Auto-attack mode (automatically engages nearby hostile mobs)
- Weapon type selection and altitude limits

---

#### Marker Blocks

Special blocks used to register geographic data for autonomous flight.
Click a placed marker to open its configuration GUI. Shift+click or use `/wingman marker type` to change its type.

| Type | Description |
|------|-------------|
| `base` | Base anchor — parent marker that groups child markers |
| `parking` | Parking spot |
| `runway_a` | Runway end A — takeoff start / landing end |
| `runway_b` | Runway end B — touchdown zone |
| `waypoint` | Aerial cruise waypoint |
| `helipad` | Vertical takeoff/landing pad for helicopters and VTOL aircraft |
| `helipad_b` | Helipad heading reference marker |

---

#### Taxi Routes

Configured via the "Taxi Routes" tab in the BASE marker GUI.
Defines ground movement paths from parking spots to runways or helipads.

---

#### Autonomous Flight Missions

Issue a mission order to an aircraft and it will execute the full sequence automatically.
You can remain on board while the aircraft flies autonomously (throttle and heading are controlled automatically).

Supported aircraft types: fixed-wing, helicopter, VTOL

**Example mission flow:**
1. Taxi from parking
2. Take off from runway A (or vertical takeoff for helicopters/VTOL)
3. Cruise through waypoints
4. Attack run over target area (ON_STATION → STRIKE_PASS)
5. Auto-RTB on low fuel or time limit
6. Land, touch down, and park

---

### Commands

All commands follow the format `/wingman <subcommand>`.

#### Wingman (CCA)

| Command | Description |
|---------|-------------|
| `/wingman follow [uuid]` | Assign the nearest aircraft (or specified UUID) as a wingman |
| `/wingman stop` | Dismiss all wingmen |
| `/wingman status` | Show all registered wingmen and their states |

#### Formation

| Command | Description |
|---------|-------------|
| `/wingman dist <side> <alt> <rear>` | Adjust formation spacing at runtime (blocks) |
| `/wingman maxwings <n>` | Set maximum wingmen per aircraft (1–64) |

#### Combat

| Command | Description |
|---------|-------------|
| `/wingman engage [uuid]` | Order wingmen to attack a target (or player's lock-on if omitted) |
| `/wingman auto` | Switch wingmen to auto-attack mode |
| `/wingman hold` | Cease attack and return to formation |
| `/wingman weapon [type\|clear]` | Set weapon type |
| `/wingman minalt [Y]` | Set minimum attack altitude (0 = no floor) |
| `/wingman maxalt [Y]` | Set maximum attack altitude (0 = no ceiling) |
| `/wingman alt clear` | Reset altitude limits |

#### UAV

| Command | Description |
|---------|-------------|
| `/wingman spawnuav [type]` | Spawn a UAV in front of the player |

#### Markers

| Command | Description |
|---------|-------------|
| `/wingman marker list` | List all markers in the world |
| `/wingman marker type <type>` | Set the type of the marker you are looking at |
| `/wingman marker id <id>` | Set the ID of the marker you are looking at |

#### Missions

| Command | Description |
|---------|-------------|
| `/wingman order dispatch <uuid>` | Dispatch a mission to the specified aircraft |
| `/wingman order abort [uuid]` | Abort mission (omit to abort all) |
| `/wingman order status` | Show active mission status |
| `/wingman order park <uuid>` | Send the specified aircraft to its parking spot |
| `/wingman gui` | Open the Mission Planner GUI |

---

### Disclaimer

- Unofficial addon — not affiliated with the original McHeli developer (Murachiki)
- Does not modify McHeli weapon performance
- Autonomous flight is experimental and may behave unexpectedly depending on terrain and aircraft type
